package com.drive.drive_manager.simulator;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class GameStateUpdate {
    private String roomId;
    private String status;
    private Map<String, PlayerState> players;
    private String hostId;
    private String currentTurnUserId;
    private String currentPhase;
    private String event;
    private String actorId;
    private String error;
    private int    sharedCounter;
    private Map<String, String> postGameChoices;
    private String targetedInstanceId;
    private String targetingSourceInstanceId;

    public static GameStateUpdate from(GameRoom room, String event, String actorId) {
        GameStateUpdate u = new GameStateUpdate();
        u.roomId            = room.getRoomId();
        u.status            = room.getStatus().name();
        u.players           = room.getPlayers();
        u.hostId            = room.getHostId();
        u.currentTurnUserId = room.getCurrentTurnUserId();
        u.currentPhase      = room.getCurrentPhase();
        u.sharedCounter     = room.getSharedCounter();
        u.event             = event;
        u.actorId           = actorId;
        u.postGameChoices   = room.getPostGameChoices().isEmpty()
                              ? null : new LinkedHashMap<>(room.getPostGameChoices());
        return u;
    }

    public static GameStateUpdate error(String roomId, String message) {
        GameStateUpdate u = new GameStateUpdate();
        u.roomId = roomId;
        u.error  = message;
        return u;
    }
}
