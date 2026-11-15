package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A deckbox: a label a user creates to group and order their own decks. A deck may sit in several deckboxes; the
 * membership lives here (deckIds), so editing a deck never touches its deckboxes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "deck_boxes")
public class DeckBox {

    @Id
    private String id;

    @Indexed
    @Field("user_id")
    private String userId;

    private String name;

    /** Key of the fixed palette (see DeckBoxController.COLORS). */
    private String color;

    /** Position among the user's deckboxes, 0 first. */
    private int order;

    @Field("deck_ids")
    private List<String> deckIds = new ArrayList<>();

    @Field("created_at")
    private Instant createdAt;
}
