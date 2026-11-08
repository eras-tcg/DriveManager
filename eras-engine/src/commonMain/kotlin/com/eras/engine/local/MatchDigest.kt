package com.eras.engine.local

import com.drive.drive_manager.simulator.engine.GameEvent
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import kotlin.jvm.JvmStatic

/**
 * A short sum of a room's game, the same on the JVM and on JavaScript, that leaves out everything the clock or the
 * player's device decides (the history's times, the players' icons, playmats and connection, the cards' pictures and
 * file ids): two runs of the same game (seed, decks and actions, the bot pumped the same way) give the same digest.
 *
 * What it sums: the room's stage, turn, phase, counters and result; each player (by id, in order) with their counters
 * and every card of every zone (instance, name, face, tapped, strength changes, materials and resources); the timeline's
 * events (sequence, type, player, card). FNV-1a 64 over the UTF-8 text, as 16 hex digits.
 *
 * It is what an offline game claims when it is sent to the server (drive_manager's offline games: the server plays the
 * game again on the JVM with the same engine and compares).
 */
object MatchDigest {

    /** The room's digest (see the object). */
    @JvmStatic
    fun of(room: GameRoom): String = fnv(text(room))

    /** The turns of the room's game: TURN_START events kept, plus the first turn (as the server's match history counts). */
    @JvmStatic
    fun turns(room: GameRoom): Int = room.timeline.count { GameEvent.TURN_START == it.type } + 1

    internal fun text(room: GameRoom): String {
        val out = StringBuilder()
        out.append(room.status.name).append('|').append(room.winnerId).append('|').append(room.endReason).append('|')
            .append(room.currentTurnUserId).append('|').append(room.currentPhase).append('|').append(room.sharedCounter)
            .append('|').append(room.idSeq).append('|').append(room.eventSeq).append('|').append(room.version)
            .append('|').append(room.firstPlayerId).append('\n')
        for (id in room.players.keys.sorted()) {
            val p = room.players[id]!!
            out.append("P ").append(id).append('|').append(p.deckId).append('|').append(p.resourceCount).append('|')
                .append(p.turnsStarted).append('|').append(p.skipOptional).append('\n')
            zone(out, "hand", p.hand)
            zone(out, "deck", p.deck)
            zone(out, "life", p.lifeStack)
            zone(out, "tribute", p.tributeZone)
            zone(out, "discard", p.discardPile)
            zone(out, "field", p.field)
        }
        for (e in room.timeline) {
            out.append("E ").append(e.seq).append('|').append(e.type).append('|').append(e.playerId).append('|')
                .append(e.cardInstanceId).append('|').append(e.fromZone).append('|').append(e.toZone).append('\n')
        }
        return out.toString()
    }

    private fun zone(out: StringBuilder, name: String, cards: List<CardSlot>) {
        out.append(name).append(':')
        for (c in cards) card(out, c)
        out.append('\n')
    }

    private fun card(out: StringBuilder, c: CardSlot) {
        out.append('[').append(c.instanceId).append('|').append(c.cardName).append('|').append(if (c.faceDown) 'd' else 'u')
            .append(if (c.tapped) 't' else 'r').append('|').append(c.strengthModifier).append('|').append(c.turnStrength)
            .append('|').append(c.laterStrength).append('|').append(c.continuousStrength).append('|').append(c.fieldSeq)
        c.materials?.forEach { card(out, it) }
        c.resources?.forEach { card(out, it) }
        out.append(']')
    }

    /** FNV-1a 64 over the text's UTF-8 bytes, as 16 hex digits. */
    internal fun fnv(text: String): String {
        var h = -0x340d631b7bdddcdbL // the offset basis, 0xcbf29ce484222325
        for (b in text.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }
}
