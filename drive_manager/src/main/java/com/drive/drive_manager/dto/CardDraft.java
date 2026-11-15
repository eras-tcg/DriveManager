package com.drive.drive_manager.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * A card being made in the site's card editor, seen only by whoever manages cards until it is published: its metadata
 * (the same fields as a published card), its design (frame, gold border, inherit and link frames, the art's framing:
 * kept as the editor sends it) and its art, stored apart (DraftArtStore). A draft of a published card points to it
 * ({@code metaId}); the published card does not change while the draft is edited.
 */
@Data
@NoArgsConstructor
@Document(collection = "card_drafts")
public class CardDraft {

    @Id
    private String id;
    /** The published card this draft changes; null for a new card. */
    private String metaId;
    private Card meta;
    private Map<String, Object> design;
    /** Where the art is stored (DraftArtStore key) and its media type; null without art. */
    private String artKey;
    private String artType;
    private Instant updatedAt;
    private String updatedBy;
}
