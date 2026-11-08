package com.drive.drive_manager.simulator.bot

import com.drive.drive_manager.simulator.platform.Clocks
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import com.drive.drive_manager.simulator.rank.RankRules
import com.drive.drive_manager.simulator.room.GameLogEntry
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState
import com.drive.drive_manager.simulator.room.RoomEngine

/**
 * The bot's edge: past the strength of the hardest bot, a stronger player faces it with an advantage, in levels (one
 * every [MMR_STEP] of MMR over it, up to [MAX]). Each level moves a Marcador at the start of a turn:
 * - levels 1–4: the bot's Marcador goes up 1 more in its turn 4, then (higher levels) in its turn 3, 2, 1;
 * - levels 5–8, besides: the rival's Marcador goes up 1 less in their turn 5, then in their turn 4, 3, 2.
 *
 * Modo solo ranked sets it from the owner's MMR; a test room may set it by hand (for the rival's side). The bot counts
 * as that much stronger for the ranks.
 */
object BotEdge {

    const val MAX = 8
    /** Each this much MMR over the hardest bot's strength: one level more. */
    const val MMR_STEP = 100
    /** The levels from which the rival's Marcador is held back too. */
    private const val RIVAL_FROM = 5

    /** The level for a player of this MMR (0 up to the hardest bot's strength). */
    @JvmStatic
    fun levelFor(mmr: Int): Int {
        val over = mmr - RankRules.BOT_MMR[BotCatalog.HARD]!!
        return max(0, min(MAX, over / MMR_STEP))
    }

    /** How strong the hardest bot is with this edge, for the ranks. */
    @JvmStatic
    fun strength(level: Int): Int {
        return RankRules.BOT_MMR[BotCatalog.HARD]!! + clamp(level) * MMR_STEP
    }

    @JvmStatic
    fun clamp(level: Int): Int {
        return max(0, min(MAX, level))
    }

    /** The bot's own turn in which its Marcador goes up 1 more (0: none). */
    @JvmStatic
    fun botTurn(level: Int): Int {
        return if (level <= 0) 0 else max(1, 5 - level)
    }

    /** The rival's own turn in which their Marcador goes up 1 less (0: none). */
    @JvmStatic
    fun rivalTurn(level: Int): Int {
        return if (level < RIVAL_FROM) 0 else 10 - clamp(level)
    }

    /** The side the edge favors: the bot of Modo solo, else (a test room) the rival's side. */
    @JvmStatic
    fun favoredSide(room: GameRoom): String? {
        if (room.players.containsKey(BotSeat.BOT)) return BotSeat.BOT
        return if (room.editor) RoomEngine.EDITOR_RIVAL else null
    }

    /**
     * The Marcador of `player` as their turn starts: `raised` (it went up 1, or it is 1 in the first turn) with the
     * edge's 1 more or 1 less in this turn of theirs (from 0 to 5), said in the history when it applies.
     */
    @JvmStatic
    fun marcador(room: GameRoom, player: PlayerState, raised: Int): Int {
        val delta = delta(room, player.userId, player.turnsStarted)
        val count = max(0, min(5, raised + delta))
        if (delta != 0 && count != max(0, min(5, raised))) {
            room.eventLog.add(GameLogEntry(Clocks.millis(), if (delta > 0)
                "Ventaja del bot: el Marcador de " + player.username + " sube 1 más en su turno " + player.turnsStarted
            else
                "Ventaja del bot: el Marcador de " + player.username + " sube 1 menos en su turno " + player.turnsStarted))
        }
        return count
    }

    /** How much more (or less) the Marcador of `playerId` goes up in their own turn `ownTurn` (0, +1 or −1). */
    @JvmStatic
    fun delta(room: GameRoom, playerId: String?, ownTurn: Int): Int {
        val level = clamp(room.botEdge)
        val favored = (if (level > 0) favoredSide(room) else null) ?: return 0
        if (favored == playerId) return if (ownTurn == botTurn(level)) 1 else 0
        return if (ownTurn == rivalTurn(level)) -1 else 0
    }
}
