package com.drive.drive_manager.simulator;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.Summons;
import com.drive.drive_manager.simulator.engine.Chain;
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
    /** A test room: its owner controls both sides (see GameRoom.editor). */
    private boolean editor;
    /** Mulligan: who already confirmed their hand. */
    private List<String> mulliganReady;
    /** Pre-room: who takes the first turn ("random" or a player id). */
    private String firstChoice;
    private String currentTurnUserId;
    private String currentPhase;
    private String event;
    private String actorId;
    private String error;
    private int    sharedCounter;
    private Map<String, String> postGameChoices;
    /** The player an event is about (the one taken out of the room by PLAYER_KICKED). */
    private String subjectUserId;
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
    /** Who took the first turn. */
    private String firstPlayerId;
    /** The player who can still use the "ventaja del iniciador", if any. */
    private String starterAdvantageUserId;
    /** Slow effects each player can activate right now (rule 11.4.1.1), by player id. */
    private Map<String, List<Chain.Activatable>> activatable;
    /** Players who already played a structure normally this turn (one per turn). */
    private List<String> structurePlayed;
    /** Cards each player can special summon now, from the hand or the Tributos, by player id. */
    private Map<String, List<String>> specialSummonable;
    /** Who won the game that just ended, and why ("lives", "surrender"). */
    private String winnerId;
    private String endReason;

    public static GameStateUpdate from(GameRoom room, String event, String actorId) {
        // Continuous effects as they are now (manual moves in editor mode record no event)
        com.drive.drive_manager.simulator.engine.EffectEngine.refreshContinuous(room);
        GameStateUpdate u = new GameStateUpdate();
        u.roomId            = room.getRoomId();
        u.status            = room.getStatus().name();
        u.players           = room.getPlayers();
        u.hostId            = room.getHostId();
        u.editor            = room.isEditor();
        u.mulliganReady     = room.getStatus() == GameRoom.RoomStatus.MULLIGAN ? new ArrayList<>(room.getMulliganReady()) : null;
        u.firstChoice       = room.getFirstChoice();
        u.currentTurnUserId = room.getCurrentTurnUserId();
        u.currentPhase      = room.getCurrentPhase();
        u.sharedCounter     = room.getSharedCounter();
        u.event             = event;
        u.actorId           = actorId;
        u.chain             = room.getChain().isEmpty() ? null : new ArrayList<>(room.getChain());
        u.prompt            = room.getPrompt();
        u.combat            = room.getCombat();
        u.firstPlayerId     = room.getFirstPlayerId();
        u.starterAdvantageUserId = room.getStarterAdvantageUserId();
        Map<String, List<Chain.Activatable>> activatable = new LinkedHashMap<>();
        for (String id : room.getPlayers().keySet()) {
            List<Chain.Activatable> list = Chain.activatable(room, id);
            if (!list.isEmpty()) activatable.put(id, list);
        }
        u.activatable       = activatable.isEmpty() ? null : activatable;
        Map<String, List<String>> specials = new LinkedHashMap<>();
        for (String id : room.getPlayers().keySet()) {
            List<String> list = Summons.specialSummonable(room, id);
            if (!list.isEmpty()) specials.put(id, list);
        }
        // Always sent (empty when nobody can): the board relies on it instead of its own checks
        u.specialSummonable = specials;
        List<String> structures = room.getPlayers().keySet().stream().filter(id -> Summons.structurePlayed(room, id)).toList();
        u.structurePlayed   = structures.isEmpty() ? null : structures;
        u.winnerId          = room.getWinnerId();
        u.endReason         = room.getEndReason();
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
