package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.bot.BotCatalog;
import com.drive.drive_manager.simulator.engine.Combat;
import com.drive.drive_manager.simulator.engine.FieldGrant;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.engine.PendingAscension;
import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.engine.PendingRemoval;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.replay.MatchSetup;
import lombok.Data;

import java.time.Instant;
import java.util.*;

/**
 * A room and the game played in it, as sent to the board (field names and order are its JSON). The fields marked
 * {@link RoomSetting} belong to the room and stay when the Sala de pruebas travels in time; the rest are the game's.
 */
@Data
public class GameRoom {

    public enum RoomStatus {
        WAITING, SELECTING_STARTER, MULLIGAN, IN_PROGRESS, POST_GAME, FINISHED;

        /** A game in course: from choosing the starters to its end. */
        public boolean isPlaying() {
            return this == SELECTING_STARTER || this == MULLIGAN || this == IN_PROGRESS;
        }
    }

    @RoomSetting private String     roomId;
    /**
     * A test room (editor mode): its owner plays alone, controlling both sides (the rival is not a real player),
     * with the manual actions and test cards. Not listed and nobody else can enter.
     */
    @RoomSetting private boolean    editor;
    /**
     * A room against the bot: its owner plays one side and the server's bot the other (seat {@link BotRooms#BOT}).
     * Not listed and nobody else can enter; the owner picks the bot's deck.
     */
    @RoomSetting private boolean    bot;
    /**
     * A ranked game (online or against the bot): its result moves the players' rank. Chosen by the owner before the
     * game; who goes first is drawn, and against the bot its level and deck are not chosen. Not for test rooms.
     */
    @RoomSetting private boolean    ranked;
    /** A room to watch a kept game again (ReplayRooms): read only, everything shown to its viewer (the host). */
    @RoomSetting private boolean    replay;
    /** A room of the ranked queue: only these two players may enter it (not listed, always ranked). Empty: open. */
    @RoomSetting private Set<String> reserved = Set.of();
    /** A test room (editor) whose rival side the bot plays: the owner tests it seeing everything (BotRooms#setEditorBot). */
    @RoomSetting private boolean    editorBot;
    /** A test room whose owner's own side a bot plays too, at selfBotLevel (BotRooms#setEditorSelfBot). */
    @RoomSetting private boolean    editorSelfBot;
    @RoomSetting private String     selfBotLevel     = BotCatalog.NORMAL;
    /** Grows with every action accepted: a bot that thought on an older copy of the room thinks again. */
    @RoomSetting private long version;
    /** Sala de pruebas: the bots wait (the owner paused them, or went back in time). */
    @RoomSetting private volatile boolean paused;
    /** Sala de pruebas: the step of the time line shown (from 0) and how many there are (see TestTimeline). */
    @RoomSetting private int timelineAt = -1;
    @RoomSetting private int timelineSize;
    /** The pause before each move of the room's bots, chosen by the owner (null: each bot's own). */
    @RoomSetting private volatile Integer botDelayMs;
    /** The bot's edge, 0–8 (BotEdge): Modo solo ranked from the owner's MMR, or a test room's choice for its rival. */
    @RoomSetting private int botEdge;
    /** How well the bot plays, chosen by the owner before the game: "easy", "normal" or "hard" (see BotRooms). */
    @RoomSetting private String     botLevel         = BotCatalog.NORMAL;
    /**
     * A lone mandatory effect is also shown to its controller to chain it (they see what triggered): rooms created by
     * the service; the engine's tests leave it off and chain it straight away.
     */
    @RoomSetting private boolean    confirmMandatory;
    private RoomStatus status           = RoomStatus.WAITING;
    private Map<String, PlayerState> players = new LinkedHashMap<>();
    @RoomSetting private String     hostId;
    private String     currentTurnUserId;
    private String     currentPhase     = "MAIN";
    private int        sharedCounter    = 0;
    @RoomSetting private Instant    createdAt        = Instant.now();
    /** Pre-room: who takes the first turn, chosen by the host ("random" or a player id). */
    private String     firstChoice      = "random";
    /** Since when nobody is connected (null while someone is): the room is kept a while so reloading does not lose it. */
    @RoomSetting private Instant    emptySince;

    private Map<String, String> starterSelections = new LinkedHashMap<>();
    private Set<String>         mulliganReady     = new HashSet<>();
    /** Players who may use the manual actions in this online game (site editors): they see their own deck and lives. */
    @RoomSetting private Set<String>         manualPlayers     = new HashSet<>();
    /** Players with the simulator's administration permission (simulator_admin): a bot may play their own side. */
    @RoomSetting private Set<String>         adminPlayers      = new HashSet<>();
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
    private List<PendingRemoval> pendingRemovals = new ArrayList<>();
    /** An Ascenso that waits for the attack its summoner declared before it (Príncipe Charles). */
    private PendingAscension pendingAscension;
    /** Cards whose owner already chose not to replace their removal: it happens now. */
    private Set<String> removalDecided = new HashSet<>();
    /** The combat in progress (rule 10), if any. */
    private Combat combat;
    /** "Todos tus aliados … ganan …": what applies to a player's side of the table until the turn ends. */
    private List<FieldGrant> fieldGrants = new ArrayList<>();
    /** Effects already used this turn (rule 11.8), keyed by the engine. Cleared when the turn changes. */
    private Set<String> usedThisTurn = new HashSet<>();
    /** Who took the first turn of the game (rule 3.0.1). */
    private String firstPlayerId;
    /** The first turn of the game is in progress: no Definitive effect can be activated (designer ruling 2026-10-30). */
    private boolean firstTurn;
    /** The second player while they still have the "ventaja del iniciador" (rule 6.2.1.4); null once used. */
    private String starterAdvantageUserId;
    /** "Terminar turno" from the main phase went through the end phase and waits for its move up to be answered. */
    private boolean endTurnPending;
    /** The start of the turn waits for its move up to be answered before the main phase. */
    private boolean startPhasePending;
    /** Who won the last game, and why: "lives" (the opponent ran out of lives) or "surrender". */
    private String winnerId;
    private String endReason;

    /**
     * Where the room's chance comes from: every shuffle and random pick of the game. A room's own, so a game can be
     * played again exactly from a seed (the bot arena plays paired games); not part of the state sent to players.
     */
    @RoomSetting private transient Random random = new Random();

    /**
     * A room only tried on (the bot's copies, a game played again): its updates carry no view for players (what may be
     * played, legal actions…), as nobody reads them; copied with the room.
     */
    @RoomSetting private transient boolean headless;

    /** Only in the bot's copies: the player whose searches of the deck's top find the best valid cards (engine/SearchHope). */
    private transient String searchHope;

    /** How the game in course started (its seed, seats and decks), to record and play it again (MatchRecorder). */
    private MatchSetup startSetup;

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
