package com.drive.drive_manager.simulator.room

import com.drive.drive_manager.simulator.platform.Platform


/** How a room is set up: a new one (friendly, ranked or a test room) and a waiting room turned into a test room. */
internal object RoomSetup {

    /**
     * A new room owned by its creator, nobody in it yet; a test room (editor) comes with its rival, played by the
     * owner, and is never ranked.
     */
    @JvmStatic
    fun newRoom(ownerId: String?, editor: Boolean, ranked: Boolean): GameRoom {
        val room = GameRoom()
        room.hostId = ownerId
        room.confirmMandatory = true
        if (editor) {
            room.editor = true
            seatTestRival(room)
        }
        room.ranked = ranked && !editor
        room.emptySince = Platform.now()
        room.roomId = Platform.randomHexId().substring(0, 8)
        return room
    }

    /** The owner, waiting alone, plays both sides: the room becomes a test room (never ranked). The refusal, or null. */
    @JvmStatic
    fun toTestRoom(room: GameRoom, userId: String?): String? {
        if (userId!! != room.hostId) return "Solo el dueño de la sala puede convertirla en sala de pruebas"
        if (room.status != GameRoom.RoomStatus.WAITING || room.players.size != 1 || room.getPlayer(userId) == null)
            return "Solo puedes pasar a la sala de pruebas mientras esperas rival"
        room.editor = true
        room.ranked = false
        seatTestRival(room)
        room.getPlayer(userId)!!.ready = false
        return null
    }

    private fun seatTestRival(room: GameRoom) {
        val rival = PlayerState(RoomService.EDITOR_RIVAL, "Rival (prueba)")
        rival.connected = true
        room.players[RoomService.EDITOR_RIVAL] = rival
    }
}
