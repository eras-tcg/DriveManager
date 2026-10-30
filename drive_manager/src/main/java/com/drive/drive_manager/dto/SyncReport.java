package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * The last check of Drive for changes (one per server instance, by its {@code drive.instance-id}): what it found since the
 * previous check, staged for an admin to review in "Gestionar cambios".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("sync_reports")
public class SyncReport {

    @Id
    private String id;

    /** "running", "done" or "error". */
    private String status;

    /** "changes" (Drive's change list from the saved page token) or "full" (Drive compared with drive_cards). */
    private String mode;

    @Field("checked_at")
    private Instant checkedAt;

    /** When the check before this one finished (what "since" means); null the first time. */
    @Field("previous_check_at")
    private Instant previousCheckAt;

    private List<Item> items;

    private String error;

    /** One staged change: the Drive file, its name and "upsert" or "delete". */
    public record Item(String fileId, String fileName, String action) {}
}
