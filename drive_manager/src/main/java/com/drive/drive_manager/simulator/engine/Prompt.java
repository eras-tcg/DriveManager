package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A choice the engine is waiting for. Only {@link #playerId} can answer it (RESOLVE_PROMPT) and it cannot be
 * dismissed: the effect does not go on until it is answered.
 */
@Data
public class Prompt {

    public enum Kind {
        /** Pick up to {@code max} of the revealed cards for the hand (only {@code validIds}); the rest go to the deck bottom in the order given. */
        TAKE_REVEALED_REST_BOTTOM,
        /** Pick between {@code min} and {@code max} of {@code cards} (only {@code validIds}). */
        PICK,
        /** Yes or no: choosing the card in {@code cards} means yes, choosing nothing means no. */
        CONFIRM
    }

    private String id;
    private Kind kind;
    private String playerId;
    private String effectId;
    /**
     * Choices that are not part of an effect: BLOCK (defender), PROVOKE (attacker's Provocar), ADVANCE (move up to
     * the Línea frontal), RITUAL (creatures for a Ritual from the hand), SPECIAL_FRONT (move up right after a
     * special summon), REINFORCE (Reforzar with a life card), LIFE_PLAY (play a utility or structure lost as a
     * life), ORDER (which of your simultaneous triggers activates next), PRIORITY (respond to the chain).
     */
    private String purpose;
    private String sourceName;
    private String title;
    /** What the player is asked to do, in Spanish. */
    private String instruction;
    /** What happens to a chosen card ("A tu mano", "Tributar", "Destruir"), shown on it. */
    private String chosenLabel;
    private List<CardSlot> cards = new ArrayList<>();
    /** Cards that may be chosen; the rest are shown but locked. */
    private List<String> validIds = new ArrayList<>();
    private int min;
    private int max;
    /** The cards that are not chosen can be reordered (they go to the deck bottom in that order). */
    private boolean reorderRest;
    /** The cards not chosen go back to the deck top instead of its bottom (in the order given, the first one on top). */
    private boolean restToTop;
    /** The card the choice is about (the creature being summoned by Ritual, the life card…). */
    private String subjectId;
    /** Ritual from the hand: the summoner already chosen (dropped onto it). */
    private String summonerId;
    /** What each card stands for in this choice (the effects it triggered), by instance id. */
    private java.util.Map<String, String> cardLabels;
    /** The effects offered in this choice, with their whole text (chaining choices). */
    private List<OfferedEffect> effects;
    /** The whole text of the effect this choice belongs to (an effect resolving or paying its cost). */
    private String effectText;

    /** An effect offered to be chained; {@code effectId} is what the player picks to chain it. */
    /** {@code inheritedFrom*}: the Recurso that gives it, for an inherited effect. */
    public record OfferedEffect(String effectId, String sourceId, String sourceName, String label, String text,
                                String inheritedFromName, String inheritedFromImage) {}
}
