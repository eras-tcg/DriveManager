package com.drive.drive_manager.dto;

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
     * The URL of this card's alternative art, null for most cards: a copy pulled from a pack may come out with it
     * (CollectionRules.ALT_ODDS). It is only a picture of the same card, never a card of its own: it has no image
     * record (drive_cards), so no list, search or pack sees it as another card.
     */
    private String altImage;
}
