package com.drive.drive_manager.simulator.room

import com.drive.drive_manager.simulator.actions.PostGameActions
import com.drive.drive_manager.simulator.actions.PreRoomActions
import com.drive.drive_manager.simulator.bot.BotSeat
import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmStatic
import kotlin.random.Random

/**
 * Who is in a room, as the rules see it (the room session's seats): joining and reconnecting, leaving, kicking,
 * closing, the ownership passed on, a player taken out. One copy for the server (`RoomMembership` adds its sessions,
 * sweeps, missions and recording) and for a game in the browser (`LocalRoom`); what only the one keeping the rooms
 * knows is asked of its [Keeper]. Called with the room's lock held (on the server).
 */
object RoomSeats {

    /** What the seats ask of whoever keeps the rooms (the server's registry and profiles, or a LocalMatch). */
    interface Keeper {
        /** The player's icon and playmat (the server looks them up in their profile). */
        fun dress(player: PlayerState)

        /** The room is closed: no action reaches it any more (the server forgets it). */
        fun closed(room: GameRoom)

        /** The player is out of the room: their sessions are forgotten. */
        fun forgetSessions(room: GameRoom, userId: String) {}
    }

    @JvmStatic
    fun join(room: GameRoom, userId: String?, action: GameAction, keeper: Keeper): GameStateUpdate {
        if (room.isFull() && !room.players.containsKey(userId)) return GameStateUpdate.error(room.roomId, SimMessages.ROOM_FULL)
        // The deck is chosen in the pre-room (SET_DECK checks it): a deckId sent here is ignored
        var username = action.getString("username")
        room.emptySince = null
        val existing = room.getPlayer(userId)
        if (existing != null) {
            existing.connected = true
            keeper.dress(existing)
            return GameStateUpdate.from(room, "RECONNECT", userId)
        }
        if (username == null) username = userId
        val player = PlayerState(userId, username)
        player.connected = true
        keeper.dress(player)
        room.players[userId!!] = player
        // The owner is whoever created the room, even if someone else enters first; a room without one is owned by
        // its first player (a creator who never enters loses it in the server's checkOwner)
        if (room.hostId == null) room.hostId = userId
        return GameStateUpdate.from(room, ActionType.JOIN_ROOM, userId)
    }

    /** Nobody is playing in it: in a test room or against the bot, its owner is gone (the other side is not a real player). */
    @JvmStatic
    fun nobodyConnected(room: GameRoom): Boolean {
        if (room.editor || room.bot) {
            val owner = if (room.hostId == null) null else room.getPlayer(room.hostId)
            return owner == null || !owner.connected
        }
        return room.players.values.none { it.connected }
    }

    /** The owner closes the room; never in the middle of a game between two players (that is conceding). */
    @JvmStatic
    fun close(room: GameRoom, userId: String?, keeper: Keeper): GameStateUpdate {
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala puede cerrarla")
        if (!room.editor && RoomGuard.inGame(room)) return GameStateUpdate.error(room.roomId, "La partida está en curso: ríndete para salir")
        room.status = GameRoom.RoomStatus.FINISHED
        val update = GameStateUpdate.from(room, "ROOM_CLOSED", userId)
        keeper.closed(room)
        return update
    }

    /** A ranked game against the bot its owner left before the end: the bot wins it. */
    @JvmStatic
    fun lostToTheBot(room: GameRoom) {
        room.winnerId = BotSeat.BOT
        room.endReason = "abandon"
        room.status = GameRoom.RoomStatus.POST_GAME
    }

    /** The owner left: a random player among the others (connected ones first) owns the room. */
    @JvmStatic
    fun passOwnership(room: GameRoom, leavingId: String?) {
        val others = room.players.values.filter { it.userId != leavingId }
        val connected = others.filter { it.connected }
        val pool = connected.ifEmpty { others }
        room.hostId = if (pool.isEmpty()) null else pool[Random.nextInt(pool.size)].userId
    }

    /**
     * Takes a player out of the room: the owner passes to another player, an empty room closes, and a game in
     * course ends: an online one is won by the one who stays (post-game); otherwise back to the pre-room. Returns
     * whether the room was closed.
     */
    @JvmStatic
    fun remove(room: GameRoom, userId: String, keeper: Keeper): Boolean {
        if (userId == room.hostId) passOwnership(room, userId)
        room.players.remove(userId)
        keeper.forgetSessions(room, userId)
        if (room.firstChoice != null && room.firstChoice == userId) room.firstChoice = "random"
        if (room.players.isEmpty()) {
            room.status = GameRoom.RoomStatus.FINISHED
            keeper.closed(room)
            return true
        }
        if (!room.editor && RoomGuard.playing(room) && room.players.size == 1) {
            // Leaving an online game loses it: the one who stays wins right away (and sees the post-game)
            room.winnerId = room.players.keys.iterator().next()
            room.endReason = "abandon"
            room.postGameChoices.clear()
            room.status = GameRoom.RoomStatus.POST_GAME
            room.players.values.forEach { it.ready = false }
            return false
        }
        if (room.status != GameRoom.RoomStatus.WAITING) {
            // Back to the pre-room with the whole deck they had (its starter still chosen)
            PostGameActions.reset(room, false)
            room.players.values.forEach { it.deck = PreRoomActions.deepCopySlots(it.originalDeck) }
            room.status = GameRoom.RoomStatus.WAITING
        }
        room.players.values.forEach { it.ready = false }
        if (nobodyConnected(room)) room.emptySince = Platform.now()
        return false
    }

    /** A player leaves the room for good (their seat is freed). */
    @JvmStatic
    fun leave(room: GameRoom, userId: String?, keeper: Keeper): GameStateUpdate {
        val player = room.getPlayer(userId) ?: return GameStateUpdate.notInRoom(room)
        val name = player.username
        if (room.editor || room.bot) {
            // A test room, or one against the bot, is its owner's alone; a ranked game left before its end is lost
            if (room.bot && room.ranked && RoomGuard.playing(room)) lostToTheBot(room)
            room.status = GameRoom.RoomStatus.FINISHED
            keeper.closed(room)
            return GameStateUpdate.from(room, "ROOM_CLOSED", userId)
        }
        val wasPlaying = RoomGuard.playing(room)
        val closed = remove(room, userId!!, keeper)
        val update = GameStateUpdate.from(room, if (closed) "ROOM_CLOSED" else "PLAYER_LEFT", userId)
        update.logEntry = name + (if (wasPlaying) " abandonó la partida" else " salió de la sala")
        return update
    }

    /** The owner takes another player out of the room (in a game, only one who is disconnected). */
    @JvmStatic
    fun kick(room: GameRoom, userId: String?, action: GameAction, keeper: Keeper): GameStateUpdate {
        if (userId!! != room.hostId) return GameStateUpdate.error(room.roomId, "Solo el dueño de la sala puede sacar jugadores")
        val targetId = action.getString("userId")
        val target = if (targetId == null) null else room.getPlayer(targetId)
        if (target == null || targetId == userId) return GameStateUpdate.error(room.roomId, SimMessages.PLAYER_NOT_FOUND)
        if (BotSeat.BOT == targetId) return GameStateUpdate.error(room.roomId, "El bot no puede salir de su sala")
        val playing = room.status != GameRoom.RoomStatus.WAITING && room.status != GameRoom.RoomStatus.POST_GAME
        if (playing && target.connected)
            return GameStateUpdate.error(room.roomId, "Durante la partida solo puedes sacar a un jugador desconectado")
        if (playing && Platform.currentTimeMillis() - target.disconnectedAt < SimulatorLimits.ABANDON_GRACE_MS)
            return GameStateUpdate.error(room.roomId, "Espera unos segundos: tu rival puede estar reconectándose")
        remove(room, targetId!!, keeper)
        val update = GameStateUpdate.from(room, "PLAYER_KICKED", userId)
        update.subjectUserId = targetId
        update.logEntry = target.username + " fue sacado de la sala"
        return update
    }
}
