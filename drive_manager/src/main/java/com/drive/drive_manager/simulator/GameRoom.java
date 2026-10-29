package com.drive.drive_manager.simulator;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.Prompt;
import lombok.Data;

import java.time.Instant;
import java.util.*;

@Data
public class GameRoom {

    public enum RoomStatus { WAITING, SELECTING_STARTER, MULLIGAN, IN_PROGRESS, POST_GAME, FINISHED }

    private String     roomId;
    /**
     * A test room (editor mode): its owner plays alone, controlling both sides (the rival is not a real player),
     * with the manual actions and test cards. Not listed and nobody else can enter.
     */
    private boolean    editor;
    /**
     * A lone mandatory effect is also shown to its controller to chain it (they see what triggered): rooms created by
     * the service; the engine's tests leave it off and chain it straight away.
     */
    private boolean    confirmMandatory;
    private RoomStatus status           = RoomStatus.WAITING;
    private Map<String, PlayerState> players = new LinkedHashMap<>();
    private String     hostId;
    private String     currentTurnUserId;
    private String     currentPhase     = "MAIN";
    private int        sharedCounter    = 0;
    private Instant    createdAt        = Instant.now();
    /** Pre-room: who takes the first turn, chosen by the host ("random" or a player id). */
    private String     firstChoice      = "random";
    /** Since when nobody is connected (null while someone is): the room is kept a while so reloading does not lose it. */
    private Instant    emptySince;

    private Map<String, String> starterSelections = new LinkedHashMap<>();
    private Set<String>         mulliganReady     = new HashSet<>();
    private Map<String, String> postGameChoices   = new LinkedHashMap<>();

    // Persists for the room's lifetime (not the client's) — survives a player
    // navigating away and reconnecting. Lost only when the room itself ends.
    private List<GameLogEntry> eventLog = new ArrayList<>();

    // --- Rules engine (simulator/engine) ---
    /** Structured timeline of rules-relevant events, in order (rules 11.6, 11.7). */
    private List<GameEvent> timeline = new ArrayList<>();
    private long eventSeq = 0;
    /** Effects in the chain; the last one resolves first (rule 11.3.0.6). */
    private List<PendingEffect> chain = new ArrayList<>();
    /** Choice the engine is waiting for, if any. */
    private Prompt prompt;
    /** Triggered effects waiting to be activated into the chain, in rules order (rule 11.7.0.5). */
    private List<PendingEffect> triggerQueue = new ArrayList<>();
    /** Events since the last chain window: they trigger effects once the current resolution ends (rule 11.6.0.4). */
    private List<GameEvent> pendingEvents = new ArrayList<>();
    private long idSeq = 0;
    /** Removals waiting for their owner to choose a replacement, in order. */
    private List<com.drive.drive_manager.simulator.engine.PendingRemoval> pendingRemovals = new ArrayList<>();
    /** An Ascenso that waits for the attack its summoner declared before it (Príncipe Charles). */
    private com.drive.drive_manager.simulator.engine.PendingAscension pendingAscension;
    /** Cards whose owner already chose not to replace their removal: it happens now. */
    private Set<String> removalDecided = new HashSet<>();
    /** The combat in progress (rule 10), if any. */
    private com.drive.drive_manager.simulator.engine.Combat combat;
    /** Effects already used this turn (rule 11.8), keyed by the engine. Cleared when the turn changes. */
    private Set<String> usedThisTurn = new HashSet<>();
    /** Who took the first turn of the game (rule 3.0.1). */
    private String firstPlayerId;
    /** The second player while they still have the "ventaja del iniciador" (rule 6.2.1.4); null once used. */
    private String starterAdvantageUserId;
    /** "Terminar turno" from the main phase went through the end phase and waits for its move up to be answered. */
    private boolean endTurnPending;
    /** The start of the turn waits for its move up to be answered before the main phase. */
    private boolean startPhasePending;
    /** Who won the last game, and why: "lives" (the opponent ran out of lives) or "surrender". */
    private String winnerId;
    private String endReason;

    /** Closed game state (rule 7.2): a chain or a choice is pending, so no new actions can start. */
    public boolean isEngineBusy() {
        return prompt != null || !chain.isEmpty() || !triggerQueue.isEmpty() || combat != null || !pendingRemovals.isEmpty() || pendingAscension != null;
    }

    public String nextId(String prefix) {
        return prefix + "-" + (++idSeq);
    }

    public boolean isFull() {
        return players.size() >= 2;
    }

    public PlayerState getPlayer(String userId) {
        return players.get(userId);
    }

    public String getOpponentId(String userId) {
        return players.keySet().stream()
                .filter(id -> !id.equals(userId))
                .findFirst()
                .orElse(null);
    }
}
