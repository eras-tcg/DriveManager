package com.drive.drive_manager.simulator.room

import kotlin.jvm.JvmStatic
import com.drive.drive_manager.simulator.room.ActionType.Flag.NOT_A_STEP
import com.drive.drive_manager.simulator.room.ActionType.Flag.OWNER_ONLY
import com.drive.drive_manager.simulator.room.ActionType.Flag.REPLAY
import com.drive.drive_manager.simulator.room.ActionType.Flag.RULES
import com.drive.drive_manager.simulator.room.ActionType.Flag.WHILE_RESOLVING

/**
 * Every action a client (or a bot) can send to a room, by its `type` string (the constant's name), with what
 * the room's rules say about it. Unknown types parse to nothing and have none of the flags.
 */
enum class ActionType(vararg flags: Flag) {
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
    enum class Flag {
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

    private val flags: Set<Flag> = setOf(*flags)

    /** Whether the action is of this type. */
    fun `is`(action: GameAction?): Boolean = action != null && name == action.type

    fun rules(): Boolean = flags.contains(RULES)
    fun ownerOnly(): Boolean = flags.contains(OWNER_ONLY)
    fun allowedWhileResolving(): Boolean = flags.contains(WHILE_RESOLVING)
    fun timelineStep(): Boolean = !flags.contains(NOT_A_STEP)
    fun allowedInReplay(): Boolean = flags.contains(REPLAY)

    companion object {
        /** The action with that `type` string, if there is one. */
        @JvmStatic
        fun parse(type: String?): ActionType? {
            if (type == null) return null
            return try {
                valueOf(type)
            } catch (unknown: IllegalArgumentException) {
                null
            }
        }

        /** The action's type, or null when it is not a known one. */
        @JvmStatic
        fun of(action: GameAction?): ActionType? = if (action == null) null else parse(action.type)

        /** Whether the action is a known type with that flag. */
        @JvmStatic
        fun has(action: GameAction?, flag: Flag?): Boolean {
            val type = of(action)
            return type != null && type.flags.contains(flag)
        }
    }
}
