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
}
