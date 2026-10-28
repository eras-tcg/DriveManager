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
    /** Combat choices that are not part of an effect: BLOCK (defender) or PROVOKE (attacker's Provocar). */
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
}
