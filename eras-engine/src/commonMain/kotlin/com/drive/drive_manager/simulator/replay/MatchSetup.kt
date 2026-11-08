package com.drive.drive_manager.simulator.replay

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmRecord
import com.drive.drive_manager.simulator.engine.frozenListCopy
import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.room.CardSlot
import com.drive.drive_manager.simulator.room.GameRoom
import com.drive.drive_manager.simulator.room.PlayerState

/**
 * How a game started, taken the moment it starts (before the starters are set aside and the decks shuffled): the
 * seed of its chance, the room's kind and each seat with its deck in order. With it and the actions played after
 * (see [MatchRecorder]) the game can be played again exactly (see [MatchReplay]). A record: the bot's
 * room copies share it instead of copying it.
 *
 * @param seed            the game's chance starts from it (every shuffle and random pick of the game)
 * @param firstChoice     who goes first as chosen in the pre-room ("random" or a player id)
 * @param idSeq           the room's counter of ids then (new cards and effects number on from it)
 * @param eventSeq        the room's counter of events then
 */
@JvmRecord
data class MatchSetup(
    val seed: Long, val editor: Boolean, val bot: Boolean, val ranked: Boolean, val confirmMandatory: Boolean,
    val hostId: String?, val firstChoice: String?, val idSeq: Long, val eventSeq: Long, val seats: List<Seat>?,
    val botEdge: Int,
) {

    /** A player's seat: who sits there and the deck they start with, in its order. */
    @JvmRecord
    data class Seat(
        val userId: String?, val username: String?, val deckId: String?, val deckName: String?, val starterCardId: String?,
        val skipOptional: Boolean, val deck: List<CardSlot>?,
    )

    /** A new room, with this id, as the game's room was just before it started (its players seated and connected). */
    fun toRoom(roomId: String?): GameRoom {
        val room = GameRoom()
        room.roomId = roomId
        room.editor = editor
        room.bot = bot
        room.ranked = ranked
        room.confirmMandatory = confirmMandatory
        room.hostId = hostId
        room.firstChoice = firstChoice
        room.idSeq = idSeq
        room.eventSeq = eventSeq
        room.botEdge = botEdge
        for (s in seats!!) {
            val p = PlayerState(s.userId, s.username)
            p.connected = true
            p.deckId = s.deckId
            p.deckName = s.deckName
            p.starterCardId = s.starterCardId
            p.skipOptional = s.skipOptional
            p.deck = PreRoomActions.deepCopySlots(s.deck)
            p.originalDeck = PreRoomActions.deepCopySlots(s.deck)
            room.players[s.userId!!] = p
        }
        return room
    }

    /** The player's username, or the id when they are not in it. */
    fun username(userId: String?): String? {
        // Stream.findFirst() throws on a null name, as before
        val seat = seats!!.firstOrNull { s -> s.userId!! == userId } ?: return userId
        return seat.username!!
    }

    companion object {
        /** The room as it is now, just before its game starts with `seed`. */
        @JvmStatic
        fun of(room: GameRoom, seed: Long): MatchSetup {
            val seats = ArrayList<Seat>()
            for (p in room.players.values) {
                seats.add(Seat(p.userId, p.username, p.deckId, p.deckName, p.starterCardId,
                        p.skipOptional, frozenListCopy(PreRoomActions.deepCopySlots(p.deck))))
            }
            return MatchSetup(seed, room.editor, room.bot, room.ranked, room.confirmMandatory, room.hostId,
                    room.firstChoice, room.idSeq, room.eventSeq, frozenListCopy(seats), room.botEdge)
        }
    }
}
