package com.drive.drive_manager.simulator;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.TurnRules;
import com.drive.drive_manager.simulator.engine.Summons;
import com.drive.drive_manager.simulator.engine.Removals;
import com.drive.drive_manager.simulator.engine.CombatRules;
import com.drive.drive_manager.simulator.engine.Chain;
import com.drive.drive_manager.simulator.engine.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.drive.drive_manager.simulator.RulesActions.*;
import static com.drive.drive_manager.simulator.TurnActions.*;
import static com.drive.drive_manager.simulator.PreRoomActions.*;
import static com.drive.drive_manager.simulator.ZoneActions.*;
import static com.drive.drive_manager.simulator.CardActions.*;

@Service
public class RoomService {

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    // STOMP sessionId -> [roomId, userId], so a SessionDisconnectEvent (browser
    // closed / network drop) can be resolved back to a player in a room.
    private final ConcurrentHashMap<String, String[]> sessionRegistry = new ConcurrentHashMap<>();

    @Autowired
    private DeckListRepository deckListRepo;

    @Autowired
    private CardRepository cardRepo;

    @Autowired
    private DriveCardRepository driveCardRepo;

    /** Missions, progression and the player's collection (none in tests: anything is played, nothing counted). */
    @Autowired(required = false)
    private MissionHooks hooks;

    @Value("${r2.public-url}")
    private String r2PublicUrl;

    /**
     * A room with nobody connected is closed. It waits this long first, so a reload or a lost connection (the
     * socket drops and comes back) does not close it.
     */
    static final java.time.Duration EMPTY_ROOM_TTL = java.time.Duration.ofSeconds(30);
    /** The rival of a test room: its owner plays it (actions with "actAs"). */
    public static final String EDITOR_RIVAL = "editor-rival";
    /** What the owner of a test room always does as themselves, whichever side they are playing. */
    static final Set<String> OWNER_ONLY = Set.of("JOIN_ROOM", "LEAVE_ROOM", "KICK_PLAYER", "CLOSE_ROOM", "SET_FIRST_PLAYER");

    /** An owner disconnected this long has left: another player owns the room. */
    static final long OWNER_GRACE_MS = 15_000;

    /** A player disconnected this long during an online game has left it: the one who stays wins (a reload is shorter). */
    private static final long ABANDON_GRACE_MS = 20_000;

    @Autowired(required = false)
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /** The players' icons, looked up when they join. */
    @Autowired(required = false)
    private com.drive.drive_manager.service.AvatarService avatarService;

    /** Simulator actions count as someone using the site (a server that sleeps waits for nobody to be left). */
    @Autowired(required = false)
    private com.drive.drive_manager.service.ActivityTracker activityTracker;

    /** Every few seconds: closes rooms left empty and passes on the ownership of owners who left. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public void sweepRooms() {
        pruneRooms();
        long now = System.currentTimeMillis();
        for (GameRoom room : new ArrayList<>(rooms.values())) {
            synchronized (room) {
                broadcast(room.getRoomId(), checkAbandoned(room, now));
                broadcast(room.getRoomId(), checkOwner(room, now));
            }
        }
    }

    /** An online game whose player has been disconnected a while: they left it, and the one who stays wins. */
    GameStateUpdate checkAbandoned(GameRoom room, long now) {
        if (room.isEditor() || !RoomGuard.playing(room) || !rooms.containsKey(room.getRoomId())) return null;
        PlayerState gone = room.getPlayers().values().stream()
                .filter(p -> !p.isConnected() && now - p.getDisconnectedAt() >= ABANDON_GRACE_MS).findFirst().orElse(null);
        if (gone == null || room.getPlayers().values().stream().noneMatch(PlayerState::isConnected)) return null;
        GameRoom.RoomStatus statusBefore = room.getStatus();
        long seqBefore = room.getEventSeq();
        removePlayer(room, gone.getUserId());
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_LEFT", gone.getUserId());
        update.setLogEntry(gone.getUsername() + " abandonó la partida");
        recordLog(room, update);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        if (hooks != null) hooks.count(room, statusBefore, seqBefore);
        return update;
    }

    /** The owner has been disconnected a while and someone else is connected: a random connected player owns the room. */
    GameStateUpdate checkOwner(GameRoom room, long now) {
        if (room.isEditor() || room.getHostId() == null || room.getPlayers().values().stream().noneMatch(PlayerState::isConnected)) return null;
        PlayerState owner = room.getPlayer(room.getHostId());
        if (owner == null) {
            // The creator never entered
            if (now - room.getCreatedAt().toEpochMilli() < OWNER_GRACE_MS) return null;
        } else if (owner.isConnected() || now - owner.getDisconnectedAt() < OWNER_GRACE_MS) {
            return null;
        }
        String previous = room.getHostId();
        passOwnership(room, previous);
        GameStateUpdate update = GameStateUpdate.from(room, "OWNER_CHANGED", previous);
        PlayerState next = room.getPlayer(room.getHostId());
        if (next != null) {
            update.setLogEntry(next.getUsername() + " es el nuevo dueño de la sala");
            recordLog(room, update);
            update.setEventLog(new ArrayList<>(room.getEventLog()));
        }
        return update;
    }

    public GameRoom createRoom() {
        return createRoom(null);
    }

    /** The one who creates the room owns it. */
    public GameRoom createRoom(String ownerId) {
        return createRoom(ownerId, false);
    }

    /** A room; a test one (editor) comes with its rival, played by the owner. */
    public GameRoom createRoom(String ownerId, boolean editor) {
        pruneRooms();
        GameRoom room = new GameRoom();
        room.setHostId(ownerId);
        room.setConfirmMandatory(true);
        if (editor) {
            room.setEditor(true);
            PlayerState rival = new PlayerState(EDITOR_RIVAL, "Rival (prueba)");
            rival.setConnected(true);
            room.getPlayers().put(EDITOR_RIVAL, rival);
        }
        // Nobody has entered yet
        room.setEmptySince(java.time.Instant.now());
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        room.setRoomId(id);
        rooms.put(id, room);
        return room;
    }

    public GameRoom getRoom(String roomId) {
        pruneRooms();
        return rooms.get(roomId);
    }

    /** Rooms that can be listed to enter: open ones, newest first. */
    public List<GameRoom> openRooms() {
        pruneRooms();
        return rooms.values().stream()
                .filter(r -> r.getStatus() != GameRoom.RoomStatus.FINISHED && !r.isEditor())
                .sorted(Comparator.comparing(GameRoom::getCreatedAt).reversed())
                .toList();
    }

    private void pruneRooms() {
        java.time.Instant limit = java.time.Instant.now().minus(EMPTY_ROOM_TTL);
        rooms.values().removeIf(r -> r.getEmptySince() != null && r.getEmptySince().isBefore(limit));
    }

    /**
     * Applies an action and sends the room its update while still holding the room's lock: the update refers to
     * the room's live lists, so it is serialized before the next action can change them.
     */
    /** Rooms with someone connected: a server that sleeps does not go to sleep while there is a game. */
    public long activeRooms() {
        return rooms.values().stream().filter(r -> r.getPlayers().values().stream().anyMatch(PlayerState::isConnected)).count();
    }

    /** {@code manual}: the player may use the board's manual actions in an online game (a site editor). */
    public void applyAndBroadcast(String roomId, String userId, GameAction action, boolean manual) {
        GameRoom lock = rooms.get(roomId);
        if (lock == null) { broadcast(roomId, applyAction(roomId, userId, action, manual)); return; }
        synchronized (lock) {
            if (manual) lock.getManualPlayers().add(userId);
            else lock.getManualPlayers().remove(userId);
            broadcast(roomId, applyAction(roomId, userId, action, manual));
        }
    }

    /** Each player gets their own view of the room (StateMask): nothing hidden from them leaves the server. */
    private void broadcast(String roomId, GameStateUpdate update) {
        StateMask.send(messagingTemplate, roomId, rooms.get(roomId), update);
    }

    /** An action of the server itself or of a test: the manual actions are allowed. */
    public GameStateUpdate applyAction(String roomId, String userId, GameAction action) {
        return applyAction(roomId, userId, action, true);
    }

    public GameStateUpdate applyAction(String roomId, String userId, GameAction action, boolean manual) {
        if (activityTracker != null) activityTracker.touch();
        // One action at a time per room (the sweep below changes rooms too)
        GameRoom lock = rooms.get(roomId);
        GameStateUpdate update;
        if (lock == null) update = apply(roomId, userId, action, manual);
        else synchronized (lock) { update = apply(roomId, userId, action, manual); }
        // Errors go to the whole room topic: tag who caused them so only that player is shown the message
        if (update != null && update.getError() != null && update.getActorId() == null) update.setActorId(userId);
        return update;
    }

    private GameStateUpdate apply(String roomId, String userId, GameAction action, boolean manual) {
        pruneRooms();
        GameRoom room = rooms.get(roomId);
        if (room == null) return GameStateUpdate.error(roomId, "La sala ya no existe");

        if (room.isEditor()) {
            if (!userId.equals(room.getHostId())) return GameStateUpdate.error(roomId, "Es una sala de prueba: solo su dueño puede jugar en ella");
            // The owner plays the rival's side too
            if (EDITOR_RIVAL.equals(action.getString("actAs")) && !OWNER_ONLY.contains(action.getType())) userId = EDITOR_RIVAL;
        }

        String refusal = RoomGuard.refusal(room, userId, action, manual);
        if (refusal != null) return GameStateUpdate.error(roomId, refusal);

        // What the action changes is counted for the players' missions afterwards (see the end of this method)
        GameRoom.RoomStatus statusBefore = room.getStatus();
        long seqBefore = room.getEventSeq();

        // Closed game state (rule 7.2): while a chain or a choice is pending, only answering it (and leaving
        // or conceding) is allowed. The board's manual actions wait until the effect is done.
        if (room.isEngineBusy() && !ALLOWED_WHILE_RESOLVING.contains(action.getType())) {
            return GameStateUpdate.error(roomId, "Hay un efecto resolviéndose: espera a que termine");
        }

        GameStateUpdate update = switch (action.getType()) {
            case "NORMAL_SUMMON"        -> handleNormalSummon(room, userId, action);
            case "RESOLVE_PROMPT"       -> handleResolvePrompt(room, userId, action);
            case "DESTROY_CARD"         -> handleDestroyCard(room, userId, action);
            case "SACRIFICE_CARD"       -> handleSacrificeCard(room, userId, action);
            case "ATTACK"               -> handleAttack(room, userId, action);
            case "PLAY_CARD"            -> handlePlayCard(room, userId, action);
            case "RITUAL_SUMMON"        -> handleRitualSummon(room, userId, action);
            case "ACTIVATE_EFFECT"      -> handleActivateEffect(room, userId, action);
            case "JOIN_ROOM"            -> handleJoin(room, userId, action);
            case "DRAW_CARD"            -> handleDraw(room, userId);
            case "MOVE_CARD"            -> handleMove(room, userId, action);
            case "FLIP_CARD"            -> handleFlip(room, userId, action);
            case "SHUFFLE_DECK"         -> handleShuffle(room, userId);
            case "ENTER_END_PHASE"      -> handleEnterEndPhase(room, userId);
            // One way to end a turn (rule 8.3: through the end phase); "PASS_TURN" is what older boards send
            case "END_TURN", "PASS_TURN" -> handleEndTurn(room, userId);
            // "FORFEIT" is what older boards sent to concede
            case "SURRENDER", "FORFEIT" -> handleSurrender(room, userId);
            case "POST_GAME_CHOICE"     -> handlePostGameChoice(room, userId, action);
            case "START_GAME"           -> handleStartGame(room, userId, action);
            case "SET_DECK"             -> handleSetDeck(room, userId, action);
            case "SET_STARTER"          -> handleSetStarter(room, userId, action);
            case "SET_FIRST_PLAYER"     -> handleSetFirstPlayer(room, userId, action);
            case "SET_READY"            -> handleSetReady(room, userId, action);
            case "LEAVE_ROOM"           -> handleLeaveRoom(room, userId);
            case "KICK_PLAYER"          -> handleKickPlayer(room, userId, action);
            case "SELECT_STARTER"       -> handleSelectStarter(room, userId, action);
            case "CONFIRM_MULLIGAN"     -> handleConfirmMulligan(room, userId, action);
            case "SET_MARKER"           -> handleSetMarker(room, userId, action);
            case "SET_SKIP_OPTIONAL"    -> handleSetSkipOptional(room, userId, action);
            case "LIFE_HEAL"            -> handleLifeHeal(room, userId);
            case "LIFE_SHUFFLE"         -> handleLifeShuffle(room, userId);
            case "LIFE_ALL_DOWN"        -> handleLifeAllDown(room, userId);
            case "LIFE_ALL_UP"          -> handleLifeAllUp(room, userId);
            case "LIFE_RESTART"         -> handleLifeRestart(room, userId);
            case "DECK_REORDER_TOP"     -> handleDeckReorderTop(room, userId, action);
            case "ZONE_REORDER"         -> handleZoneReorder(room, userId, action);
            case "DECK_INCLUDE_DISCARD" -> handleDeckIncludeDiscard(room, userId);
            case "DECK_INCLUDE_TRIBUTE" -> handleDeckIncludeTribute(room, userId);
            case "TAP_CARD"             -> handleTapCard(room, userId, action);
            case "SET_COUNTER"          -> handleSetCounter(room, userId, action);
            case "USE_RESOURCES"        -> handleUseResources(room, userId, action);
            case "GRANT_RESOURCE"           -> handleGrantResource(room, userId);
            case "MODIFY_STRENGTH"          -> handleModifyStrength(room, userId, action);
            case "ADD_MATERIAL"             -> handleAddMaterial(room, userId, action);
            case "ADD_RESOURCE"             -> handleAddResource(room, userId, action);
            case "MOVE_FROM_ATTACHMENT"     -> handleMoveFromAttachment(room, userId, action);
            case "SPECIAL_SUMMON"           -> handleSpecialSummon(room, userId, action);
            case "SWAP_WITH_MAIN"           -> handleSwapWithMain(room, userId, action);
            case "TARGET_CARD"              -> handleTargetCard(room, userId, action);
            case "REORDER_ATTACHMENT"           -> handleReorderAttachment(room, userId, action);
            case "RECLASSIFY_ATTACHMENT"        -> handleReclassifyAttachment(room, userId, action);
            case "REVEAL_TO_HAND"               -> handleRevealToHand(room, userId, action);
            case "REFRESH_CARD_METADATA"        -> handleRefreshCardMetadata(room, userId, action);
            case "REMOVE_CARD"                  -> handleRemoveCard(room, userId, action);
            case "RESTORE_CARD"                 -> handleRestoreCard(room, userId, action);
            case "CLOSE_ROOM"                   -> handleCloseRoom(room, userId);
            case "EFFECT_SUMMON_RESOLVE"        -> handleEffectSummonResolve(room, userId, action);
            case "VIEW_DECK"                    -> handleViewDeck(room, userId, action);
            case "VIEW_LIFE_STACK"              -> handleViewLifeStack(room, userId);
            case "VIEW_LIFE_CARD"               -> handleViewLifeCard(room, userId);
            case "BULK_TO_DECK_BOTTOM"           -> handleBulkToDeckBottom(room, userId, action);
            case "BULK_MOVE"                     -> handleBulkMove(room, userId, action);
            case "CREATE_DUMMY"                  -> handleCreateDummy(room, userId, action);
            default                             -> GameStateUpdate.error(roomId, "Unknown action: " + action.getType());
        };

        // "termina tu turno" (Última Línea De Defensa): once its combat and effects are over, the turn ends
        String current = room.getCurrentTurnUserId();
        if (update != null && update.getError() == null && current != null && !room.isEngineBusy()
                && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS && "MAIN".equals(room.getCurrentPhase())
                && room.getUsedThisTurn().remove(EffectEngine.END_TURN_REQUESTED + current)) {
            update = handleEnterEndPhase(room, current);
        }
        if (update != null && update.getError() == null) {
            recordLog(room, update, action);
            update.setEventLog(new ArrayList<>(room.getEventLog()));
            if (hooks != null) hooks.count(room, statusBefore, seqBefore);
        }
        return update;
    }

    static final Set<String> ALLOWED_WHILE_RESOLVING = Set.of(
            "RESOLVE_PROMPT", "JOIN_ROOM", "SURRENDER", "FORFEIT", "CLOSE_ROOM", "POST_GAME_CHOICE", "REFRESH_CARD_METADATA",
            "LEAVE_ROOM", "KICK_PLAYER");

    private void recordLog(GameRoom room, GameStateUpdate update) {
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
        if (room.getEventLog().size() > 300) room.getEventLog().remove(0);
    }

    private GameStateUpdate handleJoin(GameRoom room, String userId, GameAction action) {
        if (room.isFull() && !room.getPlayers().containsKey(userId)) {
            return GameStateUpdate.error(room.getRoomId(), "Room is full");
        }
        // The deck is chosen in the pre-room (SET_DECK checks it): a deckId sent here is ignored
        String username = action.getString("username");
        String avatar   = avatarService == null ? null : avatarService.urlOf(userId);
        room.setEmptySince(null);

        if (room.getPlayers().containsKey(userId)) {
            PlayerState existing = room.getPlayer(userId);
            existing.setConnected(true);
            existing.setAvatar(avatar);
            return GameStateUpdate.from(room, "RECONNECT", userId);
        }
        if (username == null) username = userId;

        PlayerState player = new PlayerState(userId, username);
        player.setConnected(true);
        player.setAvatar(avatar);
        room.getPlayers().put(userId, player);

        // The owner is whoever created the room, even if someone else enters first; a room without one is owned by
        // its first player (a creator who never enters loses it in checkOwner)
        if (room.getHostId() == null) room.setHostId(userId);

        return GameStateUpdate.from(room, "JOIN_ROOM", userId);
    }

    public void registerSession(String sessionId, String roomId, String userId) {
        if (sessionId == null) return;
        sessionRegistry.put(sessionId, new String[]{roomId, userId});
        GameRoom room = rooms.get(roomId);
        if (room == null) return;
        synchronized (room) {
            PlayerState player = room.getPlayer(userId);
            if (player != null && !player.isConnected()) {
                player.setConnected(true);
                room.setEmptySince(null);
            }
        }
    }

    /** Called from GameDisconnectListener when a STOMP session drops (browser closed, network lost). */
    public void handleSessionDisconnect(String sessionId) {
        String[] info = sessionRegistry.remove(sessionId);
        if (info == null) return;
        GameRoom room = rooms.get(info[0]);
        if (room == null) return;
        synchronized (room) { broadcast(room.getRoomId(), sessionDisconnected(room, info[1])); }
    }

    private GameStateUpdate sessionDisconnected(GameRoom room, String userId) {
        String roomId = room.getRoomId();
        PlayerState player = room.getPlayer(userId);
        if (player == null) return null;

        // Another session of the same player (a second tab, a reload that connected first) keeps them connected
        boolean otherSession = sessionRegistry.values().stream().anyMatch(s -> roomId.equals(s[0]) && userId.equals(s[1]));
        if (otherSession) return null;

        player.setConnected(false);
        player.setDisconnectedAt(System.currentTimeMillis());
        if (nobodyConnected(room)) {
            // Nobody left: the room closes (after a short wait, so a reload finds it)
            room.setEmptySince(java.time.Instant.now());
            return null;
        }
        // If it was the owner, sweepRooms passes the room on unless they come back soon (a reload)
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_DISCONNECTED", userId);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        return update;
    }

    /** The owner closes the room; never in the middle of a game between two players (that is conceding). */
    private GameStateUpdate handleCloseRoom(GameRoom room, String userId) {
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala puede cerrarla");
        if (!room.isEditor() && RoomGuard.inGame(room)) return GameStateUpdate.error(room.getRoomId(), "La partida está en curso: ríndete para salir");
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        GameStateUpdate update = GameStateUpdate.from(room, "ROOM_CLOSED", userId);
        rooms.remove(room.getRoomId());
        return update;
    }

    // ------------------------------------------------------------------ Test rooms (editor mode)

    /**
     * The owner of a room still waiting alone turns it into a test room: the rival's seat is taken by the side
     * they will also play. Returns the error, or null.
     */
    public String makeTestRoom(String roomId, String userId) {
        GameRoom room = rooms.get(roomId);
        if (room == null) return "La sala ya no existe";
        synchronized (room) {
            if (room.isEditor()) return null;
            if (!userId.equals(room.getHostId())) return "Solo el dueño de la sala puede jugar solo en ella";
            if (room.getStatus() != GameRoom.RoomStatus.WAITING || room.getPlayers().size() != 1 || room.getPlayer(userId) == null)
                return "Solo puedes jugar solo mientras esperas rival";
            room.setEditor(true);
            PlayerState rival = new PlayerState(EDITOR_RIVAL, "Rival (prueba)");
            rival.setConnected(true);
            room.getPlayers().put(EDITOR_RIVAL, rival);
            room.getPlayer(userId).setReady(false);
            GameStateUpdate update = GameStateUpdate.from(room, "TEST_ROOM", userId);
            update.setLogEntry(room.getPlayer(userId).getUsername() + " juega solo los dos lados (partida de prueba)");
            recordLog(room, update);
            update.setEventLog(new ArrayList<>(room.getEventLog()));
            broadcast(roomId, update);
        }
        return null;
    }

    /** Nobody is playing in it: in a test room, its owner is gone (the rival is not a real player). */
    private boolean nobodyConnected(GameRoom room) {
        if (room.isEditor()) {
            PlayerState owner = room.getHostId() == null ? null : room.getPlayer(room.getHostId());
            return owner == null || !owner.isConnected();
        }
        return room.getPlayers().values().stream().noneMatch(PlayerState::isConnected);
    }

    // ------------------------------------------------------------------ Owner, leaving and kicking

    /** The owner left: a random player among the others (connected ones first) owns the room. */
    private void passOwnership(GameRoom room, String leavingId) {
        List<PlayerState> others = room.getPlayers().values().stream().filter(p -> !p.getUserId().equals(leavingId)).toList();
        List<PlayerState> connected = others.stream().filter(PlayerState::isConnected).toList();
        List<PlayerState> pool = connected.isEmpty() ? others : connected;
        room.setHostId(pool.isEmpty() ? null : pool.get(new java.util.Random().nextInt(pool.size())).getUserId());
    }

    /**
     * Takes a player out of the room: the owner passes to another player, an empty room closes, and a game in
     * course ends: an online one is won by the one who stays (post-game); otherwise back to the pre-room. Returns
     * whether the room was closed.
     */
    private boolean removePlayer(GameRoom room, String userId) {
        if (userId.equals(room.getHostId())) passOwnership(room, userId);
        room.getPlayers().remove(userId);
        sessionRegistry.values().removeIf(s -> room.getRoomId().equals(s[0]) && userId.equals(s[1]));
        if (room.getFirstChoice() != null && room.getFirstChoice().equals(userId)) room.setFirstChoice("random");
        if (room.getPlayers().isEmpty()) {
            room.setStatus(GameRoom.RoomStatus.FINISHED);
            rooms.remove(room.getRoomId());
            return true;
        }
        if (!room.isEditor() && RoomGuard.playing(room) && room.getPlayers().size() == 1) {
            // Leaving an online game loses it: the one who stays wins right away (and sees the post-game)
            room.setWinnerId(room.getPlayers().keySet().iterator().next());
            room.setEndReason("abandon");
            room.getPostGameChoices().clear();
            room.setStatus(GameRoom.RoomStatus.POST_GAME);
            room.getPlayers().values().forEach(p -> p.setReady(false));
            return false;
        }
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) {
            // Back to the pre-room with the whole deck they had (its starter still chosen)
            resetGameState(room, false);
            room.getPlayers().values().forEach(p -> p.setDeck(deepCopySlots(p.getOriginalDeck())));
            room.setStatus(GameRoom.RoomStatus.WAITING);
        }
        room.getPlayers().values().forEach(p -> p.setReady(false));
        if (nobodyConnected(room)) room.setEmptySince(java.time.Instant.now());
        return false;
    }

    /** A player leaves the room for good (their seat is freed). */
    private GameStateUpdate handleLeaveRoom(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "No estás en la sala");
        String name = player.getUsername();
        if (room.isEditor()) {
            // A test room is its owner's alone
            room.setStatus(GameRoom.RoomStatus.FINISHED);
            rooms.remove(room.getRoomId());
            return GameStateUpdate.from(room, "ROOM_CLOSED", userId);
        }
        boolean wasPlaying = RoomGuard.playing(room);
        boolean closed = removePlayer(room, userId);
        GameStateUpdate update = GameStateUpdate.from(room, closed ? "ROOM_CLOSED" : "PLAYER_LEFT", userId);
        update.setLogEntry(name + (wasPlaying ? " abandonó la partida" : " salió de la sala"));
        return update;
    }

    /** The owner takes another player out of the room (in a game, only one who is disconnected). */
    private GameStateUpdate handleKickPlayer(GameRoom room, String userId, GameAction action) {
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala puede sacar jugadores");
        String targetId = action.getString("userId");
        PlayerState target = targetId == null ? null : room.getPlayer(targetId);
        if (target == null || targetId.equals(userId)) return GameStateUpdate.error(room.getRoomId(), "Jugador no encontrado");
        boolean playing = room.getStatus() != GameRoom.RoomStatus.WAITING && room.getStatus() != GameRoom.RoomStatus.POST_GAME;
        if (playing && target.isConnected())
            return GameStateUpdate.error(room.getRoomId(), "Durante la partida solo puedes sacar a un jugador desconectado");
        removePlayer(room, targetId);
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_KICKED", userId);
        update.setSubjectUserId(targetId);
        update.setLogEntry(target.getUsername() + " fue sacado de la sala");
        return update;
    }

    // ------------------------------------------------------------------ Pre-room

    /** Pre-room: the deck a player will use; its first Iniciador is the starter until another is chosen. */
    private GameStateUpdate handleSetDeck(GameRoom room, String userId, GameAction action) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.error(room.getRoomId(), "No estás en la sala");
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), "La partida ya empezó");
        String deckId = action.getString("deckId");
        if (deckId == null) return GameStateUpdate.error(room.getRoomId(), "Falta el mazo");
        if (!RoomGuard.usableDeck(deckListRepo, room, userId, deckId)) return GameStateUpdate.error(room.getRoomId(), "No se encontró el mazo");
        List<CardSlot> slots = resolveDeck(deckId);
        if (slots.isEmpty()) return GameStateUpdate.error(room.getRoomId(), "No se encontró el mazo");
        // Cards the engine does not run cannot be played; a test room plays them by hand (it is where they are tried)
        if (!room.isEditor() && unprogrammedIn(slots) != null) return GameStateUpdate.error(room.getRoomId(),
                "Este mazo tiene cartas que todavía no están programadas en el simulador");
        // Only with every copy in the player's collection (a test room plays anything: it is where cards are tried)
        if (!room.isEditor() && hooks != null && hooks.missing(userId, slots) > 0) {
            return GameStateUpdate.error(room.getRoomId(), "No puedes jugar este mazo: te faltan cartas de tu colección");
        }
        if (hooks != null) hooks.withFinishes(userId, slots);
        player.setDeckId(deckId);
        player.setDeckName(deckListRepo.findById(deckId).map(DeckList::getDeckName).orElse(null));
        player.setDeck(new ArrayList<>(slots));
        player.setOriginalDeck(deepCopySlots(slots));
        player.setStarterCardId(slots.stream().filter(CardSlot::isStarter).map(CardSlot::getCardId).findFirst().orElse(null));
        player.setReady(false);
        return GameStateUpdate.from(room, "SET_DECK", userId);
    }

    /** The names of a deck's cards the engine does not run in full ("×2 Roto-G0, Sicario R3-D"), or null. */
    static String unprogrammedIn(List<CardSlot> slots) {
        Map<String, Integer> names = new LinkedHashMap<>();
        for (CardSlot s : slots) if (s.getUnprogrammed() != null) names.merge(s.getCardName() != null ? s.getCardName() : "?", 1, Integer::sum);
        if (names.isEmpty()) return null;
        return names.entrySet().stream().map(e -> e.getValue() > 1 ? "×" + e.getValue() + " " + e.getKey() : e.getKey())
                .collect(Collectors.joining(", "));
    }

    private GameStateUpdate handlePostGameChoice(GameRoom room, String userId, GameAction action) {
        if (room.getStatus() != GameRoom.RoomStatus.POST_GAME)
            return GameStateUpdate.error(room.getRoomId(), "No estás en post-partida");
        String choice = action.getString("choice");
        if (choice == null) return GameStateUpdate.error(room.getRoomId(), "Falta choice");
        // Leaving frees the seat right away: the other player goes back to the pre-room
        if ("exit".equals(choice)) return handleLeaveRoom(room, userId);
        room.getPostGameChoices().put(userId, choice);
        // A test room: its owner decides for both sides (the rival is not a real player)
        if (room.isEditor()) room.getPlayers().keySet().forEach(id -> room.getPostGameChoices().put(id, choice));
        if (room.getPostGameChoices().size() >= room.getPlayers().size()) {
            boolean anyExit       = room.getPostGameChoices().values().stream().anyMatch("exit"::equals);
            // Alone (the rival left): no rematch, back to the pre-room to wait for another
            boolean anyChangeDeck = room.getPlayers().size() < 2
                    || room.getPostGameChoices().values().stream().anyMatch("change_deck"::equals);
            if (anyExit) {
                List<String> exiting = room.getPostGameChoices().entrySet().stream()
                        .filter(e -> "exit".equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
                exiting.forEach(id -> room.getPlayers().remove(id));
                room.getPostGameChoices().clear();
                room.setStatus(GameRoom.RoomStatus.FINISHED);
                if (room.getPlayers().isEmpty()) rooms.remove(room.getRoomId());
            } else if (anyChangeDeck) {
                // Back to the pre-room of the same room, with the decks they had (they may change them)
                resetGameState(room, true);
                room.getPlayers().values().forEach(p -> p.setReady(false));
                room.setStatus(GameRoom.RoomStatus.WAITING);
            } else {
                resetGameState(room, true);
                // A rematch starts right away with the same starters: who goes first is drawn again (rule 3.0.1)
                room.setFirstChoice("random");
                startFromPreRoom(room);
            }
        }
        return GameStateUpdate.from(room, "POST_GAME_CHOICE", userId);
    }

    private void resetGameState(GameRoom room, boolean keepDecks) {
        room.getPlayers().values().forEach(player -> {
            player.getHand().clear();
            player.getField().clear();
            player.getLifeStack().clear();
            player.getTributeZone().clear();
            player.getDiscardPile().clear();
            player.setResourceCount(0);
            if (keepDecks && player.getDeckId() != null) {
                player.getDeck().clear();
                player.setDeck(new ArrayList<>(resolveDeck(player.getDeckId())));
                Collections.shuffle(player.getDeck());
            } else if (!keepDecks) {
                player.getDeck().clear();
            }
        });
        room.setSharedCounter(0);
        room.setCurrentTurnUserId(null);
        room.setFirstPlayerId(null);
        room.setFirstTurn(false);
        room.setStarterAdvantageUserId(null);
        room.setWinnerId(null);
        room.setEndReason(null);
        room.setStartPhasePending(false);
        room.setEndTurnPending(false);
        room.setCurrentPhase("MAIN");
        room.getStarterSelections().clear();
        room.getMulliganReady().clear();
        room.getPostGameChoices().clear();
        // A new game starts with an empty chain, no pending choice and a fresh timeline
        room.getChain().clear();
        room.setPrompt(null);
        room.getTriggerQueue().clear();
        room.getPendingEvents().clear();
        room.setCombat(null);
        room.getUsedThisTurn().clear();
        room.getFieldGrants().clear();
        room.getTimeline().clear();
    }

    // ── Field card attachment actions ────────────────────────────────────────────

    /** "material" or "materials" (the page sends the plural, as the attachment lists are named); else resources. */
    static boolean isMaterial(String attachmentType) {
        return attachmentType != null && attachmentType.startsWith("material");
    }

    private GameStateUpdate handleRefreshCardMetadata(GameRoom room, String userId, GameAction action) {
        String cardId = action.getString("cardId");
        if (cardId == null) return GameStateUpdate.error(room.getRoomId(), "No cardId");

        DriveCard dc = driveCardRepo.findById(cardId).orElse(null);
        if (dc == null || dc.getName() == null)
            return GameStateUpdate.error(room.getRoomId(), "Card not found in drive_cards");

        List<Card> matches = cardRepo.findByCardNameIgnoreCaseAndEdition(dc.getName(), dc.getEdition());
        if (matches.isEmpty())
            return GameStateUpdate.error(room.getRoomId(), "Card metadata not found");
        Card meta = matches.get(0);

        for (PlayerState player : room.getPlayers().values()) {
            refreshSlotsInList(player.getHand(),         cardId, meta);
            refreshSlotsInList(player.getDeck(),         cardId, meta);
            refreshSlotsInList(player.getOriginalDeck(), cardId, meta);
            refreshSlotsInList(player.getLifeStack(),    cardId, meta);
            refreshSlotsInList(player.getTributeZone(),  cardId, meta);
            refreshSlotsInList(player.getDiscardPile(),  cardId, meta);
            for (CardSlot fs : player.getField()) {
                applyMeta(fs, cardId, meta);
                refreshSlotsInList(fs.getMaterials(), cardId, meta);
                refreshSlotsInList(fs.getResources(), cardId, meta);
            }
        }
        return GameStateUpdate.from(room, "REFRESH_CARD_METADATA", userId);
    }

    private void refreshSlotsInList(List<CardSlot> slots, String cardId, Card meta) {
        slots.stream().filter(s -> cardId.equals(s.getCardId())).forEach(s -> applyMeta(s, cardId, meta));
    }

    private void applyMeta(CardSlot slot, String cardId, Card meta) {
        if (!cardId.equals(slot.getCardId())) return;
        slot.setCardName(meta.getCardName());
        slot.setAltImageUrl(meta.getAltImage() == null || meta.getAltImage().isBlank() ? null : meta.getAltImage());
        slot.setCardType(meta.getCardType());
        slot.setStrength(meta.getStrength());
        slot.setSpecialSummonKind(meta.getSpecialSummonKind());
        slot.setStarter(Boolean.TRUE.equals(meta.getStarter()));
        // Printed properties the rules engine reads (cost to pay, filters by color/class/level)
        slot.setCost(meta.getCost());
        slot.setLevel(meta.getLevel());
        slot.setClasses(meta.getCardClasses() != null ? new ArrayList<>(meta.getCardClasses()) : new ArrayList<>());
        slot.setSpecialCost(meta.getSpecialCost());
        slot.setRequirement(meta.getRequirement());
        slot.setKeywords(keywordNames(meta.getKeywordEffects()));
        slot.setInheritKeywords(keywordNames(meta.getInheritKeywordEffects()));
        List<String> allKeywords = new ArrayList<>(slot.getKeywords());
        allKeywords.addAll(slot.getInheritKeywords());
        slot.setUnprogrammed(CardEffects.pending(meta.getCardName(),
                meta.getEffects() == null ? 0 : meta.getEffects().size(),
                meta.getInheritEffects() == null ? 0 : meta.getInheritEffects().size(), allKeywords));
        slot.setMentions(mentionedTypes(meta));
        List<String> colors = new ArrayList<>();
        if (meta.getColorIdentity() != null) colors.add(meta.getColorIdentity());
        if (meta.getColors() != null) meta.getColors().stream().filter(c -> !colors.contains(c)).forEach(colors::add);
        slot.setColors(colors);
    }

    /** The [tipos] written in its effects and inherited effects ("[[Reactivar]]" is a keyword, not a type). */
    public static List<String> mentionedTypes(Card meta) {
        List<String> found = new ArrayList<>();
        java.util.regex.Pattern type = java.util.regex.Pattern.compile("(?<!\\[)\\[([^\\[\\]]+)\\](?!\\])");
        List<com.drive.drive_manager.dto.Effect> all = new ArrayList<>();
        if (meta.getEffects() != null) all.addAll(meta.getEffects());
        if (meta.getInheritEffects() != null) all.addAll(meta.getInheritEffects());
        for (com.drive.drive_manager.dto.Effect e : all) {
            if (e.getEffectBlocks() == null) continue;
            for (com.drive.drive_manager.dto.Effect.effectBlock b : e.getEffectBlocks()) {
                for (String text : java.util.Arrays.asList(b.getActivationCondition(), b.getCost(), b.getResolution())) {
                    if (text == null) continue;
                    java.util.regex.Matcher m = type.matcher(text);
                    while (m.find()) {
                        String t = m.group(1).trim().toLowerCase(java.util.Locale.ROOT);
                        if (!found.contains(t)) found.add(t);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The cards whose metadata the engine does not run in full, by metadata id, with what is missing (as a card in
     * play shows it). Cards that are fully programmed are left out.
     */
    public Map<String, String> unprogrammedCards() {
        Map<String, String> pending = new java.util.LinkedHashMap<>();
        for (Card meta : cardRepo.findAll()) {
            List<String> keywords = new ArrayList<>(keywordNames(meta.getKeywordEffects()));
            keywords.addAll(keywordNames(meta.getInheritKeywordEffects()));
            String missing = CardEffects.pending(meta.getCardName(),
                    meta.getEffects() == null ? 0 : meta.getEffects().size(),
                    meta.getInheritEffects() == null ? 0 : meta.getInheritEffects().size(), keywords);
            if (missing != null) pending.put(meta.getId(), missing);
        }
        return pending;
    }

    /** "Letalidad {cantidad}" + 1 → "Letalidad 1". */
    static List<String> keywordNames(List<com.drive.drive_manager.dto.CardKeywordEffect> effects) {
        List<String> keywords = new ArrayList<>();
        if (effects == null) return keywords;
        effects.forEach(k -> {
            if (k.getKeyword() == null) return;
            String name = k.getKeyword().replaceAll("\\{[^}]*}", k.getNumber() != null ? k.getNumber() : "").trim();
            keywords.add(name.replaceAll("\\s+", " "));
        });
        return keywords;
    }

    private List<CardSlot> resolveDeck(String deckId) {
        Optional<DeckList> deckOpt = deckListRepo.findById(deckId);
        if (deckOpt.isEmpty()) return new ArrayList<>();

        List<String> fileIds = deckOpt.get().getCards();
        String baseUrl = r2PublicUrl.stripTrailing();

        // Batch 1: all DriveCards at once
        Map<String, DriveCard> driveCardMap = driveCardRepo.findAllById(fileIds)
                .stream().collect(Collectors.toMap(DriveCard::getId, dc -> dc));

        // Batch 2: all Card metadata by editions present in deck
        Set<String> editions = driveCardMap.values().stream()
                .map(DriveCard::getEdition)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Card> cardMeta = cardRepo.findByEditionIn(new ArrayList<>(editions))
                .stream().collect(Collectors.toMap(
                        c -> c.getCardName().toLowerCase() + ":" + c.getEdition(),
                        c -> c,
                        (a, b) -> a
                ));

        return new ArrayList<>(fileIds.stream().map(driveFileId -> {
            CardSlot slot = new CardSlot();
            slot.setInstanceId(UUID.randomUUID().toString());
            slot.setCardId(driveFileId);
            slot.setFaceDown(true);
            DriveCard dc = driveCardMap.get(driveFileId);
            if (dc != null) {
                slot.setImageUrl(dc.imageUrl(baseUrl));
                if (dc.getName() != null) {
                    slot.setCardName(dc.getName());
                    if (dc.getEdition() != null) {
                        Card card = cardMeta.get(dc.getName().toLowerCase() + ":" + dc.getEdition());
                        if (card != null) {
                            applyMeta(slot, driveFileId, card);
                        }
                    }
                }
            }
            return slot;
        }).toList());
    }
}
