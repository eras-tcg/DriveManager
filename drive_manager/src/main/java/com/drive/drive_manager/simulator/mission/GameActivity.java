package com.drive.drive_manager.simulator.mission;

import com.drive.drive_manager.service.ActivityLogService;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an action in a room puts in the administrators' activity log: a game that started (the room left the waiting
 * stage) and a game that ended (it reached the post-game), one event per real player, online or solo (a test room;
 * its rival is the owner). Pure.
 */
public final class GameActivity {

    private static final Set<GameRoom.RoomStatus> PLAYING = Set.of(
            GameRoom.RoomStatus.SELECTING_STARTER, GameRoom.RoomStatus.MULLIGAN, GameRoom.RoomStatus.IN_PROGRESS);

    private GameActivity() {
    }

    /** One event for the log. */
    public record Event(String userId, String action, Map<String, Object> detail) {
    }

    /**
     * The events of an action that began with the room in `statusBefore` and these players (one who left during the
     * action still gets the end of their game). The rival is kept by id: the log shows their current name.
     */
    public static List<Event> of(GameRoom room, GameRoom.RoomStatus statusBefore, Collection<String> playersBefore) {
        return of(room, statusBefore, playersBefore, Map.of());
    }

    /** The same, with `extra` added to every event's detail (an offline game says so: "offline": true). */
    public static List<Event> of(GameRoom room, GameRoom.RoomStatus statusBefore, Collection<String> playersBefore,
                                 Map<String, Object> extra) {
        List<Event> out = new ArrayList<>();
        boolean solo = room.isEditor();
        boolean started = statusBefore == GameRoom.RoomStatus.WAITING && PLAYING.contains(room.getStatus());
        boolean ended = PLAYING.contains(statusBefore) && room.getStatus() == GameRoom.RoomStatus.POST_GAME;
        if (!started && !ended) return out;
        for (String id : playersBefore) {
            if (RoomService.EDITOR_RIVAL.equals(id)) continue;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("roomId", room.getRoomId());
            detail.putAll(extra);
            if (!solo) {
                playersBefore.stream().filter(other -> !other.equals(id)).findFirst()
                        .ifPresent(other -> detail.put("rivalId", other));
            }
            if (started) {
                out.add(new Event(id, solo ? ActivityLogService.SOLO_GAME_START : ActivityLogService.ONLINE_GAME_START, detail));
            } else {
                if (!solo) detail.put("result", room.getWinnerId() == null ? "draw" : room.getWinnerId().equals(id) ? "won" : "lost");
                out.add(new Event(id, solo ? ActivityLogService.SOLO_GAME_END : ActivityLogService.ONLINE_GAME_END, detail));
            }
        }
        return out;
    }

    /** Writes the events of an action to the log, if there is one (never stops the game). */
    static void log(ActivityLogService activityLog, GameRoom room, GameRoom.RoomStatus statusBefore, Collection<String> playersBefore,
                    Map<String, Object> extra) {
        if (activityLog == null) return;
        try {
            for (Event e : of(room, statusBefore, playersBefore, extra)) activityLog.record(e.userId(), e.action(), e.detail());
        } catch (RuntimeException e) {
            // The log is for the administrators: a failure there must not break the room
        }
    }
}
