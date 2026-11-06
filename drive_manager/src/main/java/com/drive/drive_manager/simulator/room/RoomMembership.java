package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.actions.PostGameActions;
import com.drive.drive_manager.simulator.bot.BotRooms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.drive.drive_manager.simulator.actions.PreRoomActions.deepCopySlots;

/**
 * Who is in a room: joining and reconnecting, sessions dropping, leaving, kicking, closing, the ownership passed on
 * when the owner is gone, and the online games abandoned. Called with the room's lock held.
 */
public final class RoomMembership {

    private final RoomService service;
    private final RoomRegistry registry;

    RoomMembership(RoomService service, RoomRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    /** The player's icon and playmat, as their profile has them now. */
    private void dress(PlayerState player) {
        player.setAvatar(service.avatarService == null ? null : service.avatarService.urlOf(player.getUserId()));
        player.setPlaymat(service.playmatService == null ? null : service.playmatService.urlOf(player.getUserId()));
    }

    public GameStateUpdate join(GameRoom room, String userId, GameAction action) {
        if (room.isFull() && !room.getPlayers().containsKey(userId)) {
            return GameStateUpdate.error(room.getRoomId(), SimMessages.ROOM_FULL);
        }
        // The deck is chosen in the pre-room (SET_DECK checks it): a deckId sent here is ignored
        String username = action.getString("username");
        room.setEmptySince(null);

        if (room.getPlayers().containsKey(userId)) {
            PlayerState existing = room.getPlayer(userId);
            existing.setConnected(true);
            dress(existing);
            return GameStateUpdate.from(room, "RECONNECT", userId);
        }
        if (username == null) username = userId;

        PlayerState player = new PlayerState(userId, username);
        player.setConnected(true);
        dress(player);
        room.getPlayers().put(userId, player);

        // The owner is whoever created the room, even if someone else enters first; a room without one is owned by
        // its first player (a creator who never enters loses it in checkOwner)
        if (room.getHostId() == null) room.setHostId(userId);

        return GameStateUpdate.from(room, ActionType.JOIN_ROOM, userId);
    }

    /** A new session of a player already in the room: they are connected again. */
    void sessionOpened(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player != null && !player.isConnected()) {
            player.setConnected(true);
            room.setEmptySince(null);
        }
    }

    /** A session of the player dropped (already forgotten by the registry); the update for the room, or null. */
    GameStateUpdate sessionDropped(GameRoom room, String userId) {
        String roomId = room.getRoomId();
        PlayerState player = room.getPlayer(userId);
        if (player == null) return null;

        // Another session of the same player (a second tab, a reload that connected first) keeps them connected
        if (registry.hasSession(roomId, userId)) return null;

        player.setConnected(false);
        player.setDisconnectedAt(System.currentTimeMillis());
        if (nobodyConnected(room)) {
            // Nobody left: the room closes (after a short wait, so a reload finds it)
            room.setEmptySince(Instant.now());
            return null;
        }
        // If it was the owner, sweepRooms passes the room on unless they come back soon (a reload)
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_DISCONNECTED", userId);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        return update;
    }

    /** Nobody is playing in it: in a test room or against the bot, its owner is gone (the other side is not a real player). */
    private static boolean nobodyConnected(GameRoom room) {
        if (room.isEditor() || room.isBot()) {
            PlayerState owner = room.getHostId() == null ? null : room.getPlayer(room.getHostId());
            return owner == null || !owner.isConnected();
        }
        return room.getPlayers().values().stream().noneMatch(PlayerState::isConnected);
    }

    /**
     * The owner of a room still waiting alone turns it into a test room: the rival's seat is taken by the side
     * they will also play. The update for the room, or the refusal as an error.
     */
    GameStateUpdate toTestRoom(GameRoom room, String userId) {
        String refused = RoomSetup.toTestRoom(room, userId);
        if (refused != null) return GameStateUpdate.error(room.getRoomId(), refused);
        GameStateUpdate update = GameStateUpdate.from(room, "TEST_ROOM", userId);
        update.setLogEntry(room.getPlayer(userId).getUsername() + " pasa a la sala de pruebas: juega los dos lados");
        service.recordLog(room, update);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        return update;
    }

    /** The owner closes the room; never in the middle of a game between two players (that is conceding). */
    public GameStateUpdate close(GameRoom room, String userId) {
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala puede cerrarla");
        if (!room.isEditor() && RoomGuard.inGame(room)) return GameStateUpdate.error(room.getRoomId(), "La partida está en curso: ríndete para salir");
        room.setStatus(GameRoom.RoomStatus.FINISHED);
        GameStateUpdate update = GameStateUpdate.from(room, "ROOM_CLOSED", userId);
        registry.remove(room.getRoomId());
        return update;
    }

    // ------------------------------------------------------------------ Owner gone, abandons

    /** An online game whose player has been disconnected a while: they left it, and the one who stays wins. */
    GameStateUpdate checkAbandoned(GameRoom room, long now) {
        if (room.isEditor() || room.isBot() || !RoomGuard.playing(room) || registry.get(room.getRoomId()) == null) return null;
        PlayerState gone = room.getPlayers().values().stream()
                .filter(p -> !p.isConnected() && now - p.getDisconnectedAt() >= SimulatorLimits.ABANDON_GRACE_MS).findFirst().orElse(null);
        if (gone == null || room.getPlayers().values().stream().noneMatch(PlayerState::isConnected)) return null;
        GameRoom.RoomStatus statusBefore = room.getStatus();
        long seqBefore = room.getEventSeq();
        remove(room, gone.getUserId());
        service.recorder.abandon(room, gone.getUserId(), service.history);
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_LEFT", gone.getUserId());
        update.setLogEntry(gone.getUsername() + " abandonó la partida");
        service.recordLog(room, update);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        if (service.hooks != null) service.hooks.count(room, statusBefore, seqBefore);
        return update;
    }

    /** The owner has been disconnected a while and someone else is connected: a random connected player owns the room. */
    GameStateUpdate checkOwner(GameRoom room, long now) {
        if (room.isEditor() || room.isBot() || room.getHostId() == null || room.getPlayers().values().stream().noneMatch(PlayerState::isConnected)) return null;
        PlayerState owner = room.getPlayer(room.getHostId());
        if (owner == null) {
            // The creator never entered
            if (now - room.getCreatedAt().toEpochMilli() < SimulatorLimits.OWNER_GRACE_MS) return null;
        } else if (owner.isConnected() || now - owner.getDisconnectedAt() < SimulatorLimits.OWNER_GRACE_MS) {
            return null;
        }
        String previous = room.getHostId();
        passOwnership(room, previous);
        GameStateUpdate update = GameStateUpdate.from(room, "OWNER_CHANGED", previous);
        PlayerState next = room.getPlayer(room.getHostId());
        if (next != null) {
            update.setLogEntry(next.getUsername() + " es el nuevo dueño de la sala");
            service.recordLog(room, update);
            update.setEventLog(new ArrayList<>(room.getEventLog()));
        }
        return update;
    }

    /** A ranked game against the bot its owner left before the end: the bot wins it. */
    static void lostToTheBot(GameRoom room) {
        room.setWinnerId(BotRooms.BOT);
        room.setEndReason("abandon");
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
    }

    // ------------------------------------------------------------------ Owner, leaving and kicking

    /** The owner left: a random player among the others (connected ones first) owns the room. */
    private static void passOwnership(GameRoom room, String leavingId) {
        List<PlayerState> others = room.getPlayers().values().stream().filter(p -> !p.getUserId().equals(leavingId)).toList();
        List<PlayerState> connected = others.stream().filter(PlayerState::isConnected).toList();
        List<PlayerState> pool = connected.isEmpty() ? others : connected;
        room.setHostId(pool.isEmpty() ? null : pool.get(new Random().nextInt(pool.size())).getUserId());
    }

    /**
     * Takes a player out of the room: the owner passes to another player, an empty room closes, and a game in
     * course ends: an online one is won by the one who stays (post-game); otherwise back to the pre-room. Returns
     * whether the room was closed.
     */
    public boolean remove(GameRoom room, String userId) {
        if (userId.equals(room.getHostId())) passOwnership(room, userId);
        room.getPlayers().remove(userId);
        registry.forgetSessions(room.getRoomId(), userId);
        if (room.getFirstChoice() != null && room.getFirstChoice().equals(userId)) room.setFirstChoice("random");
        if (room.getPlayers().isEmpty()) {
            room.setStatus(GameRoom.RoomStatus.FINISHED);
            registry.remove(room.getRoomId());
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
            PostGameActions.reset(room, false);
            room.getPlayers().values().forEach(p -> p.setDeck(deepCopySlots(p.getOriginalDeck())));
            room.setStatus(GameRoom.RoomStatus.WAITING);
        }
        room.getPlayers().values().forEach(p -> p.setReady(false));
        if (nobodyConnected(room)) room.setEmptySince(Instant.now());
        return false;
    }

    /** A player leaves the room for good (their seat is freed). */
    public GameStateUpdate leave(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return GameStateUpdate.notInRoom(room);
        String name = player.getUsername();
        if (room.isEditor() || room.isBot()) {
            // A test room, or one against the bot, is its owner's alone; a ranked game left before its end is lost
            if (room.isBot() && room.isRanked() && RoomGuard.playing(room)) lostToTheBot(room);
            room.setStatus(GameRoom.RoomStatus.FINISHED);
            registry.remove(room.getRoomId());
            return GameStateUpdate.from(room, "ROOM_CLOSED", userId);
        }
        boolean wasPlaying = RoomGuard.playing(room);
        boolean closed = remove(room, userId);
        GameStateUpdate update = GameStateUpdate.from(room, closed ? "ROOM_CLOSED" : "PLAYER_LEFT", userId);
        update.setLogEntry(name + (wasPlaying ? " abandonó la partida" : " salió de la sala"));
        return update;
    }

    /** The owner takes another player out of the room (in a game, only one who is disconnected). */
    public GameStateUpdate kick(GameRoom room, String userId, GameAction action) {
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala puede sacar jugadores");
        String targetId = action.getString("userId");
        PlayerState target = targetId == null ? null : room.getPlayer(targetId);
        if (target == null || targetId.equals(userId)) return GameStateUpdate.error(room.getRoomId(), SimMessages.PLAYER_NOT_FOUND);
        if (BotRooms.BOT.equals(targetId)) return GameStateUpdate.error(room.getRoomId(), "El bot no puede salir de su sala");
        boolean playing = room.getStatus() != GameRoom.RoomStatus.WAITING && room.getStatus() != GameRoom.RoomStatus.POST_GAME;
        if (playing && target.isConnected())
            return GameStateUpdate.error(room.getRoomId(), "Durante la partida solo puedes sacar a un jugador desconectado");
        if (playing && System.currentTimeMillis() - target.getDisconnectedAt() < SimulatorLimits.ABANDON_GRACE_MS)
            return GameStateUpdate.error(room.getRoomId(), "Espera unos segundos: tu rival puede estar reconectándose");
        remove(room, targetId);
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_KICKED", userId);
        update.setSubjectUserId(targetId);
        update.setLogEntry(target.getUsername() + " fue sacado de la sala");
        return update;
    }
}
