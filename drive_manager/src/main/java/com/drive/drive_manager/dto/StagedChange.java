package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "staged_changes")
public class StagedChange {

    @Id
    private String id; // Drive file ID — ensures only one entry per file

    @Field("file_name")
    private String fileName;

    private String name;

    private Integer number;

    @Field("color_identity")
    private String colorIdentity;

    private String edition;

    @Field("sub_edition")
    private String subEdition;

    /** "upsert" or "delete" */
    private String action;

    @Field("staged_at")
    private Instant stagedAt;
}
