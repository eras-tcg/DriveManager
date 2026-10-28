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
        /** Pick exactly {@code min} cards from your hand. */
        PICK_FROM_HAND
    }

    private String id;
    private Kind kind;
    private String playerId;
    private String effectId;
    private String sourceName;
    private String title;
    /** What the player is asked to do, in Spanish. */
    private String instruction;
    private List<CardSlot> cards = new ArrayList<>();
    /** Cards that may be chosen; the rest are shown but locked. */
    private List<String> validIds = new ArrayList<>();
    private int min;
    private int max;
    /** The cards that are not chosen can be reordered (they go to the deck bottom in that order). */
    private boolean reorderRest;
}
