package com.drive.drive_manager.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Data
// Jackson builds decks through the empty constructor, so a request without a flag (the web sends no botDeck)
// keeps its default instead of failing on a null primitive in the all-args constructor.
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
@Document(collection = "deck_lists")
public class DeckList {

    @Id
    private String id;

    @Field("deck_name")
    private String deckName;

    private List<String> cards;        // list of card codes

    @Field("deck_image")
    private String deckImage;          // image URL of the deck cover card

    @Field("created_at")
    private Instant createdAt;

    @Field("user_id")
    private String userId;

    private String username;

    private boolean privateDeck;

    /** One of the decks the bot may play in ranked Modo solo (its pool); set by a simulator administrator. */
    @Field("bot_deck")
    private boolean botDeck;

    /** Proposed by the system (author ERAS, owner {@code SystemDecks.OWNER}): made from {@code decks/system-decks.json}, no player edits it. */
    @Field("system_deck")
    private boolean systemDeck;
}
