package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.room.GameRoom
import kotlin.jvm.JvmStatic

/**
 * The cards an effect follows ("el enemigo elegido", [com.drive.drive_manager.simulator.engine.effects.model.Action.Watch]): when one leaves the
 * field, the rest of that effect (an effect of moment WATCHED of the card that follows it) triggers, for that card.
 *
 * "Este turno": a mark in the room's marks of the turn, which end with it. While the card stays on the field: a floating effect of that card
 * ("Sigue a …", shown with it), which it loses as it leaves the field.
 */
object Watches {

    /** The floating effects that last while their card stays on the field. */
    const val WHILE_ON_FIELD = "field"

    private const val MARK = "watch|"
    private const val KEYWORD = "sigue:"

    /** A follow of the turn: controller, the card that follows, its effect and the card followed. */
    @JvmStatic
    fun mark(controllerId: String?, sourceId: String?, script: String?, cardId: String?): String =
        MARK + controllerId + "|" + sourceId + "|" + script + "|" + cardId

    /** The keyword of the floating effect of a follow while on the field: its effect and the card followed. */
    @JvmStatic
    fun keyword(script: String?, cardId: String?): String = KEYWORD + script + "|" + cardId

    /** Who follows the card that leaves the field now, and with which effect; each follow ends with it. */
    data class Follow(val controllerId: String, val sourceId: String, val script: String)

    @JvmStatic
    fun leaving(room: GameRoom, cardId: String): List<Follow> {
        val found = ArrayList<Follow>()
        for (mark in ArrayList(room.usedThisTurn)) {
            if (!mark.startsWith(MARK)) continue
            val p = mark.substring(MARK.length).split("|")
            if (p.size < 4 || p[3] != cardId) continue
            room.usedThisTurn.remove(mark)
            found.add(Follow(p[0], p[1], p[2]))
        }
        for (player in room.players.values) {
            for (card in player.field) {
                val follows = card.floating!!.filter { f -> f.keyword != null && f.keyword.startsWith(KEYWORD) && f.keyword.endsWith("|$cardId") }
                for (f in follows) {
                    card.floating!!.remove(f)
                    found.add(Follow(player.userId!!, card.instanceId!!, f.keyword!!.substring(KEYWORD.length, f.keyword.length - cardId.length - 1)))
                }
            }
        }
        return found
    }
}
