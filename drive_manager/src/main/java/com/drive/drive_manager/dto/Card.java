package com.drive.drive_manager.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * Card entity for MongoDB collection 'cards'.
 * Represents a trading card with metadata and effects.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "cards")
public class Card {

    @Id
    private String id;

    private String cardName;
    private String cardType;
    private Integer strength;
    private Integer cost;
    private Integer level;
    private Boolean starter;
    private List<String> cardClasses;
    private String edition;
    private String subEdition;
    private String colorIdentity;
    private Integer cardNumber;
    private String regulation;
    private List<Effect> effects;
    private List<Effect> inheritEffects;
    private List<CardKeywordEffect> keywordEffects;
    private List<CardKeywordEffect> inheritKeywordEffects;
    private Integer specialCost;
    private String specialSummonKind;
    private String requirement;
    private String rarity;
    private List<String> colors;
    /**
     * The card's alternative arts, empty for most cards: a copy pulled from a pack may come out with one of them (each
     * by its own odds or the shop's, ShopOdds.alt). Only pictures of the same card, never cards of their own: they
     * have no image record (drive_cards), so no list, search or pack sees them as other cards.
     */
    private List<AltArt> altArts;
    /**
     * Before a card could have several: its only alternative art. Read as the art LEGACY_ART_ID (the copies pulled
     * with it are kept as "alt-" + finish) and dropped the next time the arts change.
     */
    @JsonIgnore
    private String altImage;

    /** The id the single alternative art of before (altImage) goes by. */
    public static final String LEGACY_ART_ID = "0";

    /** The alternative arts, the one of before (altImage) included while it is kept that way. */
    public List<AltArt> getAltArts() {
        if (altArts != null && !altArts.isEmpty()) return altArts;
        if (altImage != null && !altImage.isBlank()) return List.of(new AltArt(LEGACY_ART_ID, altImage, null, null));
        return List.of();
    }
}
