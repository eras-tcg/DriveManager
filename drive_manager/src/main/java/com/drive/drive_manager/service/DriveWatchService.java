package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.dto.SyncState;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.SyncStateRepository;
import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.Channel;
import com.google.api.services.drive.model.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class DriveWatchService implements ApplicationRunner, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(DriveWatchService.class);
    private static final String STATE_ID = "drive";
    private static final String CHANGES_FIELDS =
            "newStartPageToken, nextPageToken, " +
            "changes(fileId, removed, file(id, name, mimeType, webContentLink, parents))";

    private final DriveClientFactory driveClientFactory;
    private final DriveParser driveParser;
    private final DriveCardRepository driveCardRepository;
    private final SyncStateRepository syncStateRepository;

    @Value("${drive.webhook-url:}")
    private String webhookUrl;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${ngrok.auth-token:}")
    private String ngrokAuthToken;

    // Resolved once on first channel registration — reused for renewals
    private volatile String resolvedWebhookUrl;
    private NgrokClient ngrokClient;

    public DriveWatchService(DriveClientFactory driveClientFactory,
                             DriveParser driveParser,
                             DriveCardRepository driveCardRepository,
                             SyncStateRepository syncStateRepository) {
        this.driveClientFactory = driveClientFactory;
        this.driveParser = driveParser;
        this.driveCardRepository = driveCardRepository;
        this.syncStateRepository = syncStateRepository;
    }

    // ── Startup ──────────────────────────────────────────────────────────────

    @Override
    public void run(ApplicationArguments args) {
        try {
            renewIfExpiringSoon();
        } catch (Exception e) {
            logger.error("Failed to register Drive watch channel on startup", e);
        }
    }

    @Override
    public void destroy() {
        if (ngrokClient != null) {
            try {
                ngrokClient.kill();
                logger.info("ngrok tunnel closed.");
            } catch (Exception e) {
                logger.warn("Error closing ngrok tunnel: {}", e.getMessage());
            }
        }
    }

    // ── Channel management ───────────────────────────────────────────────────

    public void renewIfExpiringSoon() throws GeneralSecurityException, IOException {
        String url = resolveWebhookUrl();
        if (url == null) {
            logger.info("No webhook URL available — webhook disabled. " +
                    "Set drive.webhook-url or ngrok.auth-token to enable it.");
            return;
        }

        SyncState state = syncStateRepository.findById(STATE_ID).orElse(null);
        boolean needsRenewal = state == null
                || state.getChannelId() == null
                || state.getChannelExpiration() == null
                || state.getChannelExpiration().isBefore(Instant.now().plus(2, ChronoUnit.DAYS));

        if (needsRenewal) {
            registerChannel(state, url);
        } else if (state != null) {
            logger.debug("Drive watch channel is valid until {}", state.getChannelExpiration());
        }
    }

    /**
     * Returns the effective webhook URL:
     *  1. drive.webhook-url if set (production)
     *  2. A new ngrok tunnel if ngrok.auth-token is set (local dev)
     *  3. null if neither is configured
     */
    private synchronized String resolveWebhookUrl() {
        if (resolvedWebhookUrl != null) return resolvedWebhookUrl;

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            resolvedWebhookUrl = webhookUrl;
            return resolvedWebhookUrl;
        }

        if (ngrokAuthToken != null && !ngrokAuthToken.isBlank()) {
            try {
                resolvedWebhookUrl = startNgrokTunnel();
            } catch (Exception e) {
                logger.error("Failed to start ngrok tunnel", e);
            }
        }

        return resolvedWebhookUrl;
    }

    private String startNgrokTunnel() {
        JavaNgrokConfig config = new JavaNgrokConfig.Builder()
                .withAuthToken(ngrokAuthToken)
                .build();

        ngrokClient = new NgrokClient.Builder()
                .withJavaNgrokConfig(config)
                .build();

        Tunnel tunnel = ngrokClient.connect(
                new CreateTunnel.Builder()
                        .withProto(Proto.HTTP)
                        .withAddr(serverPort)
                        .build()
        );

        String publicUrl = tunnel.getPublicUrl();
        // Google requires HTTPS; ngrok serves both but may return http://
        if (publicUrl.startsWith("http://")) {
            publicUrl = "https://" + publicUrl.substring(7);
        }

        String endpoint = publicUrl + "/api/drive/webhook";
        logger.info("ngrok tunnel active: {}", endpoint);
        return endpoint;
    }

    private void registerChannel(SyncState existing, String url) throws GeneralSecurityException, IOException {
        Drive drive = driveClientFactory.create();

        // Stop the old channel first to avoid duplicate notifications
        if (existing != null && existing.getChannelId() != null && existing.getResourceId() != null) {
            try {
                drive.channels().stop(
                        new Channel()
                                .setId(existing.getChannelId())
                                .setResourceId(existing.getResourceId())
                ).execute();
                logger.info("Stopped old Drive watch channel: {}", existing.getChannelId());
            } catch (Exception e) {
                logger.warn("Could not stop old channel (may have already expired): {}", e.getMessage());
            }
        }

        // Keep the existing page token so we don't miss changes between restarts
        String pageToken = (existing != null && existing.getPageToken() != null)
                ? existing.getPageToken()
                : drive.changes().getStartPageToken().execute().getStartPageToken();

        String channelId = UUID.randomUUID().toString();
        long sevenDaysMs = Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli();
        Channel registered = drive.changes()
                .watch(pageToken, new Channel()
                        .setId(channelId)
                        .setType("web_hook")
                        .setAddress(url)
                        .setExpiration(sevenDaysMs))
                .execute();

        SyncState newState = new SyncState(
                STATE_ID,
                pageToken,
                registered.getId(),
                registered.getResourceId(),
                Instant.ofEpochMilli(registered.getExpiration())
        );
        syncStateRepository.save(newState);
        logger.info("Drive watch channel registered. ID={}, expires={}", channelId, newState.getChannelExpiration());
    }

    // ── Change processing ────────────────────────────────────────────────────

    /**
     * Called by the webhook endpoint when Google notifies us of changes.
     * Fetches only the delta since the last stored page token.
     */
    public void processChanges(String incomingChannelId) {
        SyncState state = syncStateRepository.findById(STATE_ID).orElse(null);
        if (state == null) {
            logger.warn("Received webhook notification but no sync state found. Ignoring.");
            return;
        }
        if (!incomingChannelId.equals(state.getChannelId())) {
            logger.warn("Channel ID mismatch (stale notification). Expected={}, received={}",
                    state.getChannelId(), incomingChannelId);
            return;
        }

        try {
            Drive drive = driveClientFactory.create();
            String token = state.getPageToken();
            ChangeList result;

            do {
                result = drive.changes().list(token)
                        .setFields(CHANGES_FIELDS)
                        .execute();

                for (Change change : result.getChanges()) {
                    handleChange(drive, change);
                }

                token = result.getNextPageToken() != null
                        ? result.getNextPageToken()
                        : result.getNewStartPageToken();

            } while (result.getNextPageToken() != null);

            state.setPageToken(token);
            syncStateRepository.save(state);
            logger.info("Drive changes processed. Page token advanced.");

        } catch (Exception e) {
            logger.error("Error processing Drive changes", e);
        }
    }

    private void handleChange(Drive drive, Change change) throws IOException {
        String fileId = change.getFileId();

        if (Boolean.TRUE.equals(change.getRemoved())) {
            if (driveCardRepository.existsById(fileId)) {
                driveCardRepository.deleteById(fileId);
                logger.info("Deleted DriveCard for removed file: {}", fileId);
            }
            return;
        }

        File file = change.getFile();
        if (file == null || !isJpg(file)) return;

        DriveParser.ParsedFileName parsed = driveParser.parseFileName(file.getName());
        if (parsed == null) {
            logger.debug("Skipping non-matching file in changes: {}", file.getName());
            return;
        }

        String subEdition = resolveSubEdition(drive, file);

        DriveCard card = new DriveCard(
                file.getId(),
                buildDownloadUrl(file),
                file.getName(),
                parsed.name(),
                parsed.number(),
                parsed.color(),
                parsed.edition(),
                subEdition,
                Instant.now()
        );
        driveCardRepository.save(card);
        logger.info("Upserted DriveCard: {}", file.getName());
    }

    /**
     * Edition path:   E1 / MAIN|SUB1|SUB2|SUB3 / B|G|P|R|W / file.jpg
     * Structure path: STRUCTURES / ST1 / file.jpg
     */
    private String resolveSubEdition(Drive drive, File file) throws IOException {
        if (file.getParents() == null || file.getParents().isEmpty()) return null;

        String parentId = file.getParents().get(0);
        File parent = drive.files().get(parentId).setFields("id, name, parents").execute();
        String parentName = parent.getName().toUpperCase(Locale.ROOT);

        if (parentName.length() == 1 && "BGPRW".contains(parentName)) {
            if (parent.getParents() == null || parent.getParents().isEmpty()) return null;
            String sectionId = parent.getParents().get(0);
            File section = drive.files().get(sectionId).setFields("id, name").execute();
            String sectionName = section.getName().toUpperCase(Locale.ROOT);
            return "MAIN".equals(sectionName) ? null : sectionName.replace("SUB", "");
        }

        return null;
    }

    private boolean isJpg(File file) {
        String mime = file.getMimeType();
        if (mime != null && mime.equalsIgnoreCase("image/jpeg")) return true;
        String name = file.getName();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private String buildDownloadUrl(File file) {
        if (file.getWebContentLink() != null && !file.getWebContentLink().isBlank()) {
            return file.getWebContentLink();
        }
        return "https://drive.google.com/uc?export=download&id=" + file.getId();
    }
}
