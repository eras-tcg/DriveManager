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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class StagedChangeService {

    private static final Logger logger = LoggerFactory.getLogger(StagedChangeService.class);

    /** Changes applied at the same time: each one is a Drive download and an R2 upload, mostly waiting on the network. */
    static final int BATCH_SIZE = 5;

    private final StagedChangeRepository stagedChangeRepository;
    private final DriveCardRepository driveCardRepository;
    private final DriveClientFactory driveClientFactory;
    private final R2Service r2Service;
    private final ActivityTracker activityTracker;
    private final CardNames cardNames;
    /** Runs a whole apply, batch after batch, while the page asks how it goes. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("staged-apply"));
    /** The changes of one batch, at the same time. */
    private final ExecutorService batchPool = Executors.newFixedThreadPool(BATCH_SIZE, daemon("staged-apply-item"));
    private volatile ApplyProgress progress = ApplyProgress.idle();

    /**
     * How applying the changes goes: it runs on the server, so leaving the page, changing tabs or reloading does not
     * stop it, and the page shows it again when it comes back. {@code batch} counts from 1 while it runs.
     */
    public record ApplyProgress(boolean running, int total, int done, int failed, String current, List<String> errors,
                                Instant startedAt, Instant finishedAt, int batch, int batches) {
        static ApplyProgress idle() {
            return new ApplyProgress(false, 0, 0, 0, null, List.of(), null, null, 0, 0);
        }
    }

    public StagedChangeService(StagedChangeRepository stagedChangeRepository,
                               DriveCardRepository driveCardRepository,
                               DriveClientFactory driveClientFactory,
                               R2Service r2Service,
                               ActivityTracker activityTracker,
                               CardNames cardNames) {
        this.stagedChangeRepository = stagedChangeRepository;
        this.driveCardRepository = driveCardRepository;
        this.driveClientFactory = driveClientFactory;
        this.r2Service = r2Service;
        this.activityTracker = activityTracker;
        this.cardNames = cardNames;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public List<StagedChange> listAll() {
        return stagedChangeRepository.findAll();
    }

    public ApplyProgress progress() {
        return progress;
    }

    /** Starts applying every staged change in the background. */
    public ApplyProgress startApplyAll() {
        return startApply(null);
    }

    /**
     * Starts applying the given staged changes (all of them when {@code ids} is null or empty) in the background, in
     * batches of {@link #BATCH_SIZE} (one request for all of them timed out). Returns how it goes; if a run is already
     * going, nothing new starts.
     */
    public synchronized ApplyProgress startApply(Collection<String> ids) {
        if (progress.running()) return progress;
        List<StagedChange> chosen = ids == null || ids.isEmpty()
                ? stagedChangeRepository.findAll()
                : stagedChangeRepository.findAllById(new LinkedHashSet<>(ids)).stream().toList();
        int batches = (chosen.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        progress = new ApplyProgress(!chosen.isEmpty(), chosen.size(), 0, 0, null, List.of(), Instant.now(),
                chosen.isEmpty() ? Instant.now() : null, chosen.isEmpty() ? 0 : 1, batches);
        if (!chosen.isEmpty()) worker.submit(() -> applyInBackground(chosen, batches));
        return progress;
    }

    private void applyInBackground(List<StagedChange> all, int batches) {
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        Instant started = progress.startedAt();
        for (int b = 0; b < batches; b++) {
            List<StagedChange> batch = all.subList(b * BATCH_SIZE, Math.min(all.size(), (b + 1) * BATCH_SIZE));
            String label = batch.stream().map(StagedChangeService::label).collect(Collectors.joining(", "));
            progress = new ApplyProgress(true, all.size(), done.get(), failed.get(), label, List.copyOf(errors), started, null, b + 1, batches);
            // A server that sleeps when nobody uses it must not sleep in the middle of this
            activityTracker.touch();
            List<Future<?>> running = new ArrayList<>();
            for (StagedChange change : batch) {
                running.add(batchPool.submit(() -> {
                    try {
                        // Discarded or applied by hand meanwhile: nothing to do
                        if (stagedChangeRepository.existsById(change.getId())) applySingle(change);
                        done.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        logger.error("Failed to apply staged change {}", change.getId(), e);
                        if (errors.size() < 20) errors.add(label(change) + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                }));
            }
            for (Future<?> f : running) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    // each task catches its own errors
                }
            }
        }
        progress = new ApplyProgress(false, all.size(), done.get(), failed.get(), null, List.copyOf(errors), started, Instant.now(), batches, batches);
    }

    private static String label(StagedChange change) {
        return change.getName() != null ? change.getName() : change.getFileName();
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
                // The file name has no real hyphens: the metadata's name, when there is one
                cardNames.nameFor(change.getEdition(), change.getNumber(), change.getSubEdition(), change.getColorIdentity(), change.getName()),
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
