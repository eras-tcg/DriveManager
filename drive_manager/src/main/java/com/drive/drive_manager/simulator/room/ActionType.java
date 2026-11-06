package com.drive.drive_manager.simulator.room;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static com.drive.drive_manager.simulator.room.ActionType.Flag.*;

/**
 * Every action a client (or a bot) can send to a room, by its {@code type} string (the constant's name), with what
 * the room's rules say about it. Unknown types parse to nothing and have none of the flags.
 */
public enum ActionType {
    NORMAL_SUMMON(RULES),
    RESOLVE_PROMPT(RULES, WHILE_RESOLVING),
    DESTROY_CARD,
    SACRIFICE_CARD(RULES),
    ATTACK(RULES),
    PLAY_CARD(RULES),
    RITUAL_SUMMON(RULES),
    ACTIVATE_EFFECT(RULES),
    JOIN_ROOM(RULES, OWNER_ONLY, WHILE_RESOLVING, NOT_A_STEP, REPLAY),
    DRAW_CARD,
    MOVE_CARD,
    FLIP_CARD,
    SHUFFLE_DECK,
    ENTER_END_PHASE(RULES),
    END_TURN(RULES),
    /** What older boards send to end the turn (same as END_TURN). */
    PASS_TURN(RULES),
    SURRENDER(RULES, WHILE_RESOLVING),
    /** What older boards sent to concede (same as SURRENDER). */
    FORFEIT(RULES, WHILE_RESOLVING),
    POST_GAME_CHOICE(RULES, WHILE_RESOLVING),
    START_GAME(RULES),
    SET_DECK(RULES),
    SET_STARTER(RULES),
    SET_BOT_LEVEL(RULES, OWNER_ONLY, NOT_A_STEP),
    SET_EDITOR_BOT(OWNER_ONLY, NOT_A_STEP),
    SET_EDITOR_SELF_BOT(OWNER_ONLY, NOT_A_STEP),
    SET_BOT_DELAY(RULES, OWNER_ONLY, NOT_A_STEP),
    /** A test room's owner gives the rival's side the bot's edge (BotEdge). */
    SET_BOT_EDGE(RULES, OWNER_ONLY, NOT_A_STEP),
    SET_FIRST_PLAYER(RULES, OWNER_ONLY),
    SET_PAUSED(OWNER_ONLY, WHILE_RESOLVING, NOT_A_STEP, REPLAY),
    TIME_STEP(OWNER_ONLY, WHILE_RESOLVING, NOT_A_STEP, REPLAY),
    SET_RANKED(RULES, OWNER_ONLY),
    SET_READY(RULES),
    LEAVE_ROOM(RULES, OWNER_ONLY, WHILE_RESOLVING, REPLAY),
    KICK_PLAYER(RULES, OWNER_ONLY, WHILE_RESOLVING),
    SELECT_STARTER(RULES),
    CONFIRM_MULLIGAN(RULES),
    SET_MARKER,
    SET_SKIP_OPTIONAL(RULES, NOT_A_STEP),
    LIFE_HEAL,
    LIFE_SHUFFLE,
    LIFE_ALL_DOWN,
    LIFE_ALL_UP,
    LIFE_RESTART,
    DECK_REORDER_TOP,
    ZONE_REORDER,
    DECK_INCLUDE_DISCARD,
    DECK_INCLUDE_TRIBUTE,
    TAP_CARD,
    SET_COUNTER,
    USE_RESOURCES,
    GRANT_RESOURCE,
    MODIFY_STRENGTH,
    ADD_MATERIAL,
    ADD_RESOURCE,
    MOVE_FROM_ATTACHMENT,
    SPECIAL_SUMMON,
    SWAP_WITH_MAIN,
    TARGET_CARD(RULES),
    REORDER_ATTACHMENT,
    RECLASSIFY_ATTACHMENT,
    REVEAL_TO_HAND,
    REFRESH_CARD_METADATA(WHILE_RESOLVING),
    REMOVE_CARD,
    RESTORE_CARD,
    CLOSE_ROOM(RULES, OWNER_ONLY, WHILE_RESOLVING),
    EFFECT_SUMMON_RESOLVE,
    VIEW_DECK,
    VIEW_LIFE_STACK,
    VIEW_LIFE_CARD,
    BULK_TO_DECK_BOTTOM,
    BULK_MOVE,
    CREATE_DUMMY;

    /** What the room's rules say about an action. */
    public enum Flag {
        /** Any player can send it in an online game: the rules engine's actions and the room's own flow. */
        RULES,
        /** The owner of a test room always does it as themselves, whichever side they are playing. */
        OWNER_ONLY,
        /** Allowed while a chain or a choice is pending (closed game state, rule 7.2). */
        WHILE_RESOLVING,
        /** It does not move the game: not a step of a test room's time line. */
        NOT_A_STEP,
        /** The viewer of a replay may send it: the room's own flow and the time line. */
        REPLAY
    }

    private final Set<Flag> flags;

    ActionType(Flag... flags) {
        this.flags = flags.length == 0 ? EnumSet.noneOf(Flag.class) : EnumSet.of(flags[0], flags);
    }

    /** The action with that {@code type} string, if there is one. */
    public static Optional<ActionType> parse(String type) {
        if (type == null) return Optional.empty();
        try {
            return Optional.of(valueOf(type));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** The action's type, or null when it is not a known one. */
    public static ActionType of(GameAction action) {
        return action == null ? null : parse(action.getType()).orElse(null);
    }

    /** Whether the action is of this type. */
    public boolean is(GameAction action) {
        return action != null && name().equals(action.getType());
    }

    /** Whether the action is a known type with that flag. */
    public static boolean has(GameAction action, Flag flag) {
        ActionType type = of(action);
        return type != null && type.flags.contains(flag);
    }

    boolean rules() { return flags.contains(RULES); }
    boolean ownerOnly() { return flags.contains(OWNER_ONLY); }
    boolean allowedWhileResolving() { return flags.contains(WHILE_RESOLVING); }
    boolean timelineStep() { return !flags.contains(NOT_A_STEP); }
    boolean allowedInReplay() { return flags.contains(REPLAY); }
}
