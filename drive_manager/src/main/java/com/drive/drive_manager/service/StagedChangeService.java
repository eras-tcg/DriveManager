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
import java.util.List;

@Service
public class StagedChangeService {

    private static final Logger logger = LoggerFactory.getLogger(StagedChangeService.class);

    private final StagedChangeRepository stagedChangeRepository;
    private final DriveCardRepository driveCardRepository;
    private final DriveClientFactory driveClientFactory;
    private final R2Service r2Service;

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

    /** Apply all staged changes. Returns count of applied entries. */
    public int applyAll() throws GeneralSecurityException, IOException {
        List<StagedChange> all = stagedChangeRepository.findAll();
        for (StagedChange change : all) {
            applySingle(change);
        }
        return all.size();
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
