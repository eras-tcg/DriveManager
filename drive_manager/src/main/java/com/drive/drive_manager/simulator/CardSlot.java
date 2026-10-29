package com.drive.drive_manager.simulator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardSlot {
    private String instanceId;
    private String cardId;
    private String imageUrl;
    private boolean faceDown;
    private boolean tapped;
    private Double x;
    private Double y;
    private String cardName;
    private String cardType;
    private Integer strength;
    private String specialSummonKind;
    private boolean starter;
    private int strengthModifier = 0;
    /** Printed properties used by the rules engine (null when the card has no metadata). */
    private Integer cost;
    private Integer level;
    /** Color identity plus extra colors (B, G, P, R, W). */
    private List<String> colors = new ArrayList<>();
    private List<String> classes = new ArrayList<>();
    /** Special summon cost and Requerimiento as printed ("criatura nivel 2", "criaturas que en nivel sumen 6"). */
    private Integer specialCost;
    private String requirement;
    /** Printed keyword abilities ("Provocar", "Letalidad 1", "Imbloqueable"). */
    private List<String> keywords = new ArrayList<>();
    /** Abilities gained until the end of the turn ("gana Furia 1 hasta el final del turno"). */
    private List<String> turnKeywords = new ArrayList<>();
    /**
     * Timeline seq when it entered the field: a card on the field only reacts to events from then on (an event
     * that happened before it was there, like the sacrifice of its own Ritual summoner, cannot trigger it).
     */
    private long fieldSeq;
    /** Strength gained or lost until the end of the turn ("gana fuerza 3 hasta el final del turno"). */
    private int turnStrength;
    /** Strength lost until the end of the next turn ("hasta el final del turno oponente / del próximo turno"). */
    private int laterStrength;
    /** Strength and abilities its continuous effects give it while their condition holds (checked after every event). */
    private int continuousStrength;
    /** Placed "hasta el final del turno" (Janne): it goes back to its owner's hand when the turn ends. */
    private boolean returnAtTurnEnd;
    private List<String> continuousKeywords = new ArrayList<>();
    /** What effects gave or took from it for a while, with their source (shown in its details). */
    private List<FloatingEffect> floating = new ArrayList<>();
    /**
     * "no se reactiva al inicio del turno oponente": 2 while the effect's turn lasts, 1 during its owner's next turn
     * (it is not reactivated then), 0 otherwise. Counted down when the turn changes.
     */
    private int noUntap;
    private List<CardSlot> materials = new ArrayList<>();
    private List<CardSlot> resources = new ArrayList<>();
}
