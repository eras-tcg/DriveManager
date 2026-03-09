package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "deck_lists")
public class DeckList {

    @Id
    private String id;

    @Field("deck_name")
    private String deckName;

    private List<String> cards;        // list of card codes

    @Field("deck_image")
    private String deckImage;          // card code of the most relevant card (must be in cards)

    @Field("created_at")
    private Instant createdAt;

    @Field("user_id")
    private String userId;

    private String username;
}
