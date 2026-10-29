package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.dto.StagedChange;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.StagedChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class StagedChangeService {

    private static final Logger logger = LoggerFactory.getLogger(StagedChangeService.class);

    private final StagedChangeRepository stagedChangeRepository;
    private final DriveCardRepository driveCardRepository;
    private final DriveClientFactory driveClientFactory;
    private final R2Service r2Service;
    /** Applying everything runs here, one change at a time, while the page asks how it goes. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "staged-apply");
        t.setDaemon(true);
        return t;
    });
    private volatile ApplyProgress progress = ApplyProgress.idle();

    /**
     * How applying all the changes goes: it runs on the server, so leaving the page (or reloading it) does not
     * stop it, and the page shows it again when it comes back.
     */
    public record ApplyProgress(boolean running, int total, int done, int failed, String current, List<String> errors,
                                Instant startedAt, Instant finishedAt) {
        static ApplyProgress idle() {
            return new ApplyProgress(false, 0, 0, 0, null, List.of(), null, null);
        }
    }

    public StagedChangeService(StagedChangeRepository stagedChangeRepository,
                               DriveCardRepository driveCardRepository,
                               DriveClientFactory driveClientFactory,
                               R2Service r2Service) {
        this.stagedChangeRepository = stagedChangeRepository;
        this.driveCardRepository = driveCardRepository;
        this.driveClientFactory = driveClientFactory;
        this.r2Service = r2Service;
    }

    public List<StagedChange> listAll() {
        return stagedChangeRepository.findAll();
    }

    public ApplyProgress progress() {
        return progress;
    }

    /**
     * Starts applying every staged change in the background (one image at a time: one request for all of them
     * timed out). Returns how it goes; if it is already running, nothing new starts.
     */
    public synchronized ApplyProgress startApplyAll() {
        if (progress.running()) return progress;
        List<StagedChange> all = stagedChangeRepository.findAll();
        progress = new ApplyProgress(true, all.size(), 0, 0, null, List.of(), Instant.now(), null);
        worker.submit(() -> applyInBackground(all));
        return progress;
    }

    private void applyInBackground(List<StagedChange> all) {
        int done = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        Instant started = progress.startedAt();
        for (StagedChange change : all) {
            String label = change.getName() != null ? change.getName() : change.getFileName();
            progress = new ApplyProgress(true, all.size(), done, failed, label, List.copyOf(errors), started, null);
            try {
                // Discarded or applied by hand meanwhile: nothing to do
                if (stagedChangeRepository.existsById(change.getId())) applySingle(change);
                done++;
            } catch (Exception e) {
                failed++;
                logger.error("Failed to apply staged change {}", change.getId(), e);
                if (errors.size() < 20) errors.add(label + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        progress = new ApplyProgress(false, all.size(), done, failed, null, List.copyOf(errors), started, Instant.now());
    }

    /** Apply one staged change by Drive file ID. */
    public void applyOne(String fileId) throws GeneralSecurityException, IOException {
        StagedChange change = stagedChangeRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("No staged change for fileId: " + fileId));
        applySingle(change);
    }

    /** Discard a staged change without applying it. */
    public void discard(String fileId) {
        stagedChangeRepository.deleteById(fileId);
        logger.info("Discarded staged change for fileId: {}", fileId);
    }

    private void applySingle(StagedChange change) throws GeneralSecurityException, IOException {
        String fileId = change.getId();

        if ("delete".equals(change.getAction())) {
            r2Service.delete(fileId);
            driveCardRepository.deleteById(fileId);
            stagedChangeRepository.deleteById(fileId);
            logger.info("Applied DELETE for: {}", fileId);
            return;
        }

        // Download image bytes from Drive using the authenticated client
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        driveClientFactory.create()
                .files()
                .get(fileId)
                .executeMediaAndDownloadTo(baos);
        byte[] bytes = baos.toByteArray();

        r2Service.upload(fileId, new ByteArrayInputStream(bytes), bytes.length);

        DriveCard card = new DriveCard(
                fileId,
                change.getFileName(),
                change.getName(),
                change.getNumber(),
                change.getColorIdentity(),
                change.getEdition(),
                change.getSubEdition(),
                Instant.now()
        );
        driveCardRepository.save(card);
        stagedChangeRepository.deleteById(fileId);
        logger.info("Applied UPSERT for: {}", change.getFileName());
    }
}
