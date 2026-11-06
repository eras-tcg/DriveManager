package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.service.ActivityTracker;
import com.drive.drive_manager.service.AvatarService;
import com.drive.drive_manager.service.PlaymatService;
import com.drive.drive_manager.simulator.actions.ActionDispatcher;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.mission.MissionHooks;
import com.drive.drive_manager.simulator.rank.RankService;
import com.drive.drive_manager.simulator.replay.MatchHistory;
import com.drive.drive_manager.simulator.replay.MatchRecorder;
import com.drive.drive_manager.simulator.replay.MatchSetup;
import com.drive.drive_manager.simulator.replay.ReplayRooms;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.drive.drive_manager.simulator.actions.TurnActions.handleEnterEndPhase;

/**
 * The simulator's rooms: the entry point of the controllers, the bots and the tests. Each action goes through the
 * guards here (one at a time per room) to {@link ActionDispatcher}, and what it changed is then logged, counted and
 * recorded. The rooms are kept by {@link RoomRegistry}; who is in them is {@link RoomMembership}'s.
 */
@Service
public class RoomService {

    /** The decks and the cards' metadata (none in tests, the bot's copies and replays: they never load a deck). */
    @Autowired(required = false) public DeckLoader decks;
    /** Missions, progression and the player's collection (none in tests: anything is played, nothing counted). */
    @Autowired(required = false) public MissionHooks hooks;
    /** The players' game history (none in tests); each game is recorded as it goes to be kept and played again. */
    @Autowired(required = false) MatchHistory history;
    /** The ranked ladder (none in tests): a ranked game against the bot is played at the owner's strength. */
    @Autowired(required = false) public RankService ranks;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;
    /** Who is connected and from how many tabs: each tab gets the room on its own (StateMask.toUser). */
    @Autowired(required = false)
    private SimpUserRegistry userRegistry;
    /** The players' icons and playmats, looked up when they join. */
    @Autowired(required = false) AvatarService avatarService;
    @Autowired(required = false) PlaymatService playmatService;
    /** Simulator actions count as someone using the site (a server that sleeps waits for nobody to be left). */
    @Autowired(required = false)
    private ActivityTracker activityTracker;

    /** The rival of a test room: its owner plays it (actions with "actAs"). */
    public static final String EDITOR_RIVAL = "editor-rival";

    private final RoomRegistry registry = new RoomRegistry();
    public final MatchRecorder recorder = new MatchRecorder();
    /** The rooms played against the bot, and when their bot moves. */
    public final BotRooms botRooms = new BotRooms(this);
    public final TestTimeline timeline = new TestTimeline(botRooms);
    private final ReplayRooms replays = new ReplayRooms();
    public final RoomMembership members = new RoomMembership(this, registry);
    private final ActionDispatcher dispatcher = new ActionDispatcher(this);

    /** Every few seconds: closes rooms left empty and passes on the ownership of owners who left. */
    @Scheduled(fixedDelay = SimulatorLimits.SWEEP_INTERVAL_MS)
    public void sweepRooms() {
        pruneRooms();
        botRooms.wakeAll();
        long now = System.currentTimeMillis();
        for (GameRoom room : registry.snapshot()) {
            synchronized (room) {
                broadcast(room.getRoomId(), members.checkAbandoned(room, now));
                broadcast(room.getRoomId(), members.checkOwner(room, now));
            }
        }
    }

    /** A friendly online room nobody owns yet. */
    public GameRoom createRoom() {
        return createRoom(null);
    }

    /** The one who creates the room owns it. */
    public GameRoom createRoom(String ownerId) {
        return createRoom(ownerId, RoomOptions.ONLINE);
    }

    /** A room of the given kind; a test one (editor) comes with its rival, played by the owner; one against the bot, with the bot seated. */
    public GameRoom createRoom(String ownerId, RoomOptions options) {
        pruneRooms();
        GameRoom room = RoomSetup.newRoom(ownerId, options.editor(), options.ranked());
        registry.put(room);
        if (options.bot() && !options.editor()) botRooms.seat(room);
        return room;
    }

    public GameRoom getRoom(String roomId) {
        pruneRooms();
        return registry.get(roomId);
    }

    /** Rooms that can be listed to enter: open ones, newest first. */
    public List<GameRoom> openRooms() {
        pruneRooms();
        return registry.listed();
    }

    /** Rooms with someone connected: a server that sleeps does not go to sleep while there is a game. */
    public long activeRooms() {
        return registry.active();
    }

    private void pruneRooms() {
        // Its owner gone for good in the middle of a ranked game against the bot: lost, and kept as such
        registry.prune(r -> {
            RoomMembership.lostToTheBot(r);
            recorder.abandon(r, r.getHostId(), history);
        });
        Set<String> open = registry.ids();
        botRooms.keepOnly(open);
        recorder.keepOnly(open);
        timeline.keepOnly(open);
        replays.keepOnly(open);
    }

    private static GameStateUpdate withActor(GameStateUpdate update, String userId) {
        update.setActorId(userId);
        return update;
    }

    public void applyAndBroadcast(String roomId, String userId, GameAction action, boolean manual) {
        applyAndBroadcast(roomId, userId, action, manual, false);
    }

    /**
     * Applies an action and sends the room its update while still holding the room's lock: the update refers to
     * the room's live lists, so it is serialized before the next action can change them. {@code manual}: the player
     * may use the board's manual actions in an online game (a site editor); {@code admin}: they administer the
     * simulator (a bot may play their own side).
     */
    public void applyAndBroadcast(String roomId, String userId, GameAction action, boolean manual, boolean admin) {
        GameRoom lock = registry.get(roomId);
        // The bots' pace, at once even while a bot thinks with the room held; the board shows it and the next update has it
        if (lock != null && ActionType.SET_BOT_DELAY.is(action)) {
            String refused = botRooms.pace(lock, userId, action);
            if (refused != null) broadcast(roomId, withActor(GameStateUpdate.error(roomId, refused), userId));
            return;
        }
        if (lock == null) { broadcast(roomId, applyAction(roomId, userId, action, manual)); return; }
        synchronized (lock) {
            if (manual) lock.getManualPlayers().add(userId);
            else lock.getManualPlayers().remove(userId);
            if (admin) lock.getAdminPlayers().add(userId);
            else lock.getAdminPlayers().remove(userId);
            broadcast(roomId, applyAction(roomId, userId, action, manual));
        }
        botRooms.wake(roomId);
    }

    /** Each player gets their own view of the room (StateMask): nothing hidden from them leaves the server. */
    public void broadcast(String roomId, GameStateUpdate update) {
        StateMask.send(messagingTemplate, userRegistry, roomId, registry.get(roomId), update);
    }

    /** An action of the server itself or of a test: the manual actions are allowed. */
    public GameStateUpdate applyAction(String roomId, String userId, GameAction action) {
        return applyAction(roomId, userId, action, true);
    }

    public GameStateUpdate applyAction(String roomId, String userId, GameAction action, boolean manual) {
        if (activityTracker != null) activityTracker.touch();
        // One action at a time per room (the sweep below changes rooms too)
        GameRoom lock = registry.get(roomId);
        GameStateUpdate update;
        if (lock == null) update = apply(roomId, userId, action, manual);
        else synchronized (lock) { update = apply(roomId, userId, action, manual); }
        // Errors go to the whole room topic: tag who caused them so only that player is shown the message
        if (update != null && update.getError() != null && update.getActorId() == null) update.setActorId(userId);
        return update;
    }

    private GameStateUpdate apply(String roomId, String userId, GameAction action, boolean manual) {
        pruneRooms();
        GameRoom room = registry.get(roomId);
        if (room == null) return GameStateUpdate.error(roomId, SimMessages.ROOM_GONE);
        if (room.isReplay()) return replays.act(this, room, userId, action);
        ActionType type = ActionType.of(action);

        if (room.isEditor()) {
            if (!userId.equals(room.getHostId())) return GameStateUpdate.error(roomId, "Es una sala de pruebas: solo su dueño puede jugar en ella");
            // The owner plays the rival's side too
            if (EDITOR_RIVAL.equals(action.getString("actAs")) && (type == null || !type.ownerOnly())) userId = EDITOR_RIVAL;
        }

        if (BotRooms.forBot(room, action)) {
            if (!userId.equals(room.getHostId())) return GameStateUpdate.error(roomId, "Solo el dueño de la sala elige el mazo del bot");
            if (room.isRanked()) return GameStateUpdate.error(roomId, "En una partida ranked el bot juega con tu mismo mazo");
            userId = BotRooms.BOT;
        }

        String refusal = RoomGuard.refusal(room, userId, action, manual);
        if (refusal != null) return GameStateUpdate.error(roomId, refusal);

        // What the action changes is counted for the players' missions afterwards (see the end of this method)
        GameRoom.RoomStatus statusBefore = room.getStatus();
        long seqBefore = room.getEventSeq();

        // Closed game state (rule 7.2): while a chain or a choice is pending, only answering it (and leaving
        // or conceding) is allowed. The board's manual actions wait until the effect is done.
        if (room.isEngineBusy() && (type == null || !type.allowedWhileResolving())) {
            return GameStateUpdate.error(roomId, "Hay un efecto resolviéndose: espera a que termine");
        }

        GameStateUpdate update = dispatcher.dispatch(type, room, userId, action);

        // "termina tu turno" (Última Línea De Defensa): once its combat and effects are over, the turn ends
        String current = room.getCurrentTurnUserId();
        if (update != null && update.getError() == null && current != null && !room.isEngineBusy()
                && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && "MAIN".equals(room.getCurrentPhase())
                && room.getUsedThisTurn().remove(EffectEngine.END_TURN_REQUESTED + current)) {
            update = handleEnterEndPhase(room, current);
        }
        if (update != null && update.getError() == null) {
            room.setVersion(room.getVersion() + 1);
            recordLog(room, update, action);
            if (!room.isHeadless()) update.setEventLog(new ArrayList<>(room.getEventLog()));
            if (hooks != null) hooks.count(room, statusBefore, seqBefore);
            recorder.after(room, statusBefore, userId, action, manual, history);
            timeline.after(room, statusBefore, action);
        }
        return update;
    }

    public void recordLog(GameRoom room, GameStateUpdate update) {
        recordLog(room, update, null);
    }

    /** A line of the history for an action by hand (or of the room), with the card it was done to when there is one. */
    private void recordLog(GameRoom room, GameStateUpdate update, GameAction action) {
        if (update.getLogEntry() == null) return;
        GameLogEntry entry = new GameLogEntry(System.currentTimeMillis(), update.getLogEntry());
        if (action != null) {
            entry.setKind("MANUAL");
            String id = action.getString("instanceId");
            CardSlot card = id == null ? null : EffectEngine.findInRoom(room, id);
            if (card != null) {
                entry.setSourceId(id);
                entry.setSourceName(card.getCardName());
                entry.setImageUrl(card.getImageUrl());
            }
        }
        room.getEventLog().add(entry);
        if (room.getEventLog().size() > SimulatorLimits.EVENT_LOG_MAX) room.getEventLog().remove(0);
    }

    // ------------------------------------------------------------------ Sessions

    public void registerSession(String sessionId, String roomId, String userId) {
        if (sessionId == null) return;
        registry.registerSession(sessionId, roomId, userId);
        GameRoom room = registry.get(roomId);
        if (room == null) return;
        synchronized (room) { members.sessionOpened(room, userId); }
    }

    /** Called from GameDisconnectListener when a STOMP session drops (browser closed, network lost). */
    public void handleSessionDisconnect(String sessionId) {
        String[] info = registry.dropSession(sessionId);
        if (info == null) return;
        GameRoom room = registry.get(info[0]);
        if (room == null) return;
        synchronized (room) { broadcast(room.getRoomId(), members.sessionDropped(room, info[1])); }
    }

    // ------------------------------------------------------------------ Test rooms, replays and the bots' rooms

    /**
     * The owner of a room still waiting alone turns it into a test room: the rival's seat is taken by the side
     * they will also play. Returns the error, or null.
     */
    public String makeTestRoom(String roomId, String userId) {
        GameRoom room = registry.get(roomId);
        if (room == null) return SimMessages.ROOM_GONE;
        synchronized (room) {
            if (room.isEditor()) return null;
            GameStateUpdate update = members.toTestRoom(room, userId);
            if (update.getError() != null) return update.getError();
            broadcast(roomId, update);
        }
        return null;
    }

    /** A room to watch a kept game again, for its viewer (see ReplayRooms). */
    public GameRoom openReplay(String viewerId, MatchSetup setup, List<MatchRecorder.Played> actions) {
        pruneRooms();
        GameRoom room = replays.open(viewerId, setup, actions);
        registry.put(room);
        return room;
    }

    /** A room still open (no pruning first): for the bots, which run apart from the players' actions. */
    public GameRoom liveRoom(String roomId) {
        return registry.get(roomId);
    }

    /** A room copy the bot tries moves on (see {@link Sandbox}): kept here, apart from the real rooms, until dropped. */
    public void adopt(GameRoom copy) {
        registry.put(copy);
    }

    public void drop(String roomId) {
        registry.remove(roomId);
    }

    /** Plays the bot's moves right away until it has none (tests). Returns how many it made. */
    public int playBotNow(String roomId) {
        return botRooms.playNow(roomId);
    }

    /**
     * The cards whose metadata the engine does not run in full, by metadata id, with what is missing (as a card in
     * play shows it). Cards that are fully programmed are left out.
     */
    public Map<String, String> unprogrammedCards() {
        return decks == null ? Map.of() : decks.unprogrammedCards();
    }
}
