package com.drive.drive_manager.simulator.room;

import java.time.Instant;
import java.util.UUID;

/** How a room is set up: a new one (friendly, ranked or a test room) and a waiting room turned into a test room. */
final class RoomSetup {

    private RoomSetup() {}

    /**
     * A new room owned by its creator, nobody in it yet; a test room (editor) comes with its rival, played by the
     * owner, and is never ranked.
     */
    static GameRoom newRoom(String ownerId, boolean editor, boolean ranked) {
        GameRoom room = new GameRoom();
        room.setHostId(ownerId);
        room.setConfirmMandatory(true);
        if (editor) {
            room.setEditor(true);
            seatTestRival(room);
        }
        room.setRanked(ranked && !editor);
        room.setEmptySince(Instant.now());
        room.setRoomId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        return room;
    }

    /** The owner, waiting alone, plays both sides: the room becomes a test room (never ranked). The refusal, or null. */
    static String toTestRoom(GameRoom room, String userId) {
        if (!userId.equals(room.getHostId())) return "Solo el dueño de la sala puede convertirla en sala de pruebas";
        if (room.getStatus() != GameRoom.RoomStatus.WAITING || room.getPlayers().size() != 1 || room.getPlayer(userId) == null)
            return "Solo puedes pasar a la sala de pruebas mientras esperas rival";
        room.setEditor(true);
        room.setRanked(false);
        seatTestRival(room);
        room.getPlayer(userId).setReady(false);
        return null;
    }

    private static void seatTestRival(GameRoom room) {
        PlayerState rival = new PlayerState(RoomService.EDITOR_RIVAL, "Rival (prueba)");
        rival.setConnected(true);
        room.getPlayers().put(RoomService.EDITOR_RIVAL, rival);
    }
}
