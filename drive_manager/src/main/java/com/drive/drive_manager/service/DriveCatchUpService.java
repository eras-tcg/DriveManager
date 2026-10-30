package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.dto.StagedChange;
import com.drive.drive_manager.dto.SyncReport;
import com.drive.drive_manager.dto.SyncState;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.repository.StagedChangeRepository;
import com.drive.drive_manager.repository.SyncReportRepository;
import com.drive.drive_manager.repository.SyncStateRepository;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Catches up with Drive when the server wakes up (a server that sleeps receives no webhook): reads Drive's change list from
 * this instance's saved page token and stages what changed since the last check. Without a token, or when Drive no longer
 * accepts it, it compares Drive with drive_cards instead and stages only the differences. Either way the result is saved
 * as this instance's {@link SyncReport}, for the admins to review.
 */
@Service
public class DriveCatchUpService {

    private static final Logger logger = LoggerFactory.getLogger(DriveCatchUpService.class);

    private final DriveWatchService driveWatchService;
    private final DriveParser driveParser;
    private final SyncStateRepository syncStateRepository;
    private final SyncReportRepository syncReportRepository;
    private final DriveCardRepository driveCardRepository;
    private final StagedChangeRepository stagedChangeRepository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drive-catch-up");
        t.setDaemon(true);
        return t;
    });

    @Value("${drive.instance-id:drive}")
    private String stateId;

    @Value("${drive.catch-up-on-start:false}")
    private boolean catchUpOnStart;

    public DriveCatchUpService(DriveWatchService driveWatchService, DriveParser driveParser, SyncStateRepository syncStateRepository,
                               SyncReportRepository syncReportRepository, DriveCardRepository driveCardRepository,
                               StagedChangeRepository stagedChangeRepository) {
        this.driveWatchService = driveWatchService;
        this.driveParser = driveParser;
        this.syncStateRepository = syncStateRepository;
        this.syncReportRepository = syncReportRepository;
        this.driveCardRepository = driveCardRepository;
        this.stagedChangeRepository = stagedChangeRepository;
    }

    /** The first routine of a server that was asleep (DRIVE_CATCH_UP_ON_START=true). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (catchUpOnStart) start();
    }

    /** The last check (running, done or failed), or null if there was none. */
    public SyncReport report() {
        return syncReportRepository.findById(stateId).orElse(null);
    }

    /** Starts a check in the background, unless one is already running; returns its report. */
    public synchronized SyncReport start() {
        SyncReport last = report();
        if (last != null && "running".equals(last.getStatus())) return last;
        Instant previous = last == null ? null : "done".equals(last.getStatus()) ? last.getCheckedAt() : last.getPreviousCheckAt();
        SyncReport running = new SyncReport(stateId, "running", null, Instant.now(), previous, List.of(), null);
        syncReportRepository.save(running);
        worker.submit(() -> run(running));
        return running;
    }

    private void run(SyncReport report) {
        try {
            SyncState state = syncStateRepository.findById(stateId).orElse(null);
            List<StagedChange> staged = null;
            String nextToken = null;
            String mode = "changes";
            if (state != null && state.getPageToken() != null) {
                try {
                    DriveWatchService.ChangesResult changes = driveWatchService.fetchChanges(state.getPageToken());
                    staged = changes.staged();
                    nextToken = changes.nextToken();
                } catch (GoogleJsonResponseException e) {
                    // An old or invalid page token: compare everything instead
                    logger.warn("Drive no longer accepts the saved page token ({}): comparing Drive with drive_cards", e.getStatusCode());
                }
            }
            if (staged == null) {
                mode = "full";
                // Taken before the comparison, so nothing changed during it is missed next time
                nextToken = driveWatchService.startPageToken();
                staged = compareWithDrive();
            }
            SyncState next = state == null ? new SyncState(stateId, nextToken, null, null, null) : state;
            next.setPageToken(nextToken);
            syncStateRepository.save(next);

            List<SyncReport.Item> items = staged.stream().map(s -> new SyncReport.Item(s.getId(), s.getFileName(), s.getAction())).toList();
            report.setStatus("done");
            report.setMode(mode);
            report.setCheckedAt(Instant.now());
            report.setItems(items);
            syncReportRepository.save(report);
            logger.info("Drive catch-up ({}): {} change(s) staged", mode, items.size());
        } catch (Exception e) {
            logger.error("Drive catch-up failed", e);
            report.setStatus("error");
            report.setCheckedAt(Instant.now());
            report.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            syncReportRepository.save(report);
        }
    }

    /**
     * Drive compared with drive_cards: an image that is new or whose file name, edition, number or color changed is staged as an
     * upsert; a card image that is no longer in Drive, as a delete.
     */
    List<StagedChange> compareWithDrive() throws Exception {
        List<StagedChange> inDrive = driveParser.scan(List.of(), List.of(), List.of());
        List<DriveCard> cards = driveCardRepository.findAll();
        // A scan that sees (almost) nothing is a Drive problem, not every card gone
        if (inDrive.isEmpty() || inDrive.size() < cards.size() / 2) {
            throw new IllegalStateException("Drive devolvió " + inDrive.size() + " imágenes para " + cards.size()
                    + " cartas registradas: no se comparó");
        }
        Map<String, DriveCard> byId = new HashMap<>();
        cards.forEach(c -> byId.put(c.getId(), c));
        List<StagedChange> staged = new ArrayList<>();
        for (StagedChange file : inDrive) {
            DriveCard card = byId.remove(file.getId());
            if (card == null || !sameCard(card, file)) staged.add(file);
        }
        Instant now = Instant.now();
        for (DriveCard gone : byId.values()) {
            staged.add(new StagedChange(gone.getId(), gone.getFileName(), gone.getName(), gone.getNumber(), gone.getColorIdentity(),
                    gone.getEdition(), gone.getSubEdition(), "delete", now));
        }
        stagedChangeRepository.saveAll(staged);
        return staged;
    }

    /** The same file in the same place. The name is not compared: an admin may have corrected it in drive_cards. */
    static boolean sameCard(DriveCard card, StagedChange file) {
        return Objects.equals(card.getFileName(), file.getFileName())
                && Objects.equals(card.getNumber(), file.getNumber()) && Objects.equals(card.getColorIdentity(), file.getColorIdentity())
                && Objects.equals(card.getEdition(), file.getEdition()) && Objects.equals(card.getSubEdition(), file.getSubEdition());
    }
}
