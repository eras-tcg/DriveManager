package com.drive.drive_manager.simulator;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.Prompt;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private String revealedCardImageUrl;
    private String revealedCardName;
    private String logEntry;
    private List<GameLogEntry> eventLog;
    /** Latest timeline events (structured). */
    private List<GameEvent> timeline;
    /** Effects in the chain (the last one resolves first). */
    private List<PendingEffect> chain;
    /** Choice being waited for; only prompt.playerId may answer. */
    private Prompt prompt;
    /** Combat in progress, if any. */
    private com.drive.drive_manager.simulator.engine.Combat combat;

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
        u.chain             = room.getChain().isEmpty() ? null : new ArrayList<>(room.getChain());
        u.prompt            = room.getPrompt();
        u.combat            = room.getCombat();
        List<GameEvent> tl  = room.getTimeline();
        u.timeline          = tl.isEmpty() ? null : new ArrayList<>(tl.subList(Math.max(0, tl.size() - 100), tl.size()));
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
