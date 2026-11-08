package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.simulator.mission.MissionHooks;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Who is in a room, on the server: the rules of joining, leaving, kicking, closing and taking a player out are the room
 * session's ({@link RoomSeats}, common with the game in the browser); here are the server's parts: the players' profiles
 * (icon, playmat), the registry (a closed room is forgotten, a player's sessions too), sessions dropping, and the sweeps
 * (the ownership passed on when the owner is gone, the online games abandoned, with missions and recording). Called with
 * the room's lock held.
 */
public final class RoomMembership {

    private final RoomService service;
    private final RoomRegistry registry;

    RoomMembership(RoomService service, RoomRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    /** What the room session's seats ask of the server: profiles and the registry. */
    private final RoomSeats.Keeper keeper = new RoomSeats.Keeper() {
        /** The player's icon and playmat, as their profile has them now. */
        @Override
        public void dress(PlayerState player) {
            player.setAvatar(service.avatarService == null ? null : service.avatarService.urlOf(player.getUserId()));
            player.setPlaymat(service.playmatService == null ? null : service.playmatService.urlOf(player.getUserId()));
        }

        @Override
        public void closed(GameRoom room) {
            registry.remove(room.getRoomId());
        }

        @Override
        public void forgetSessions(GameRoom room, String userId) {
            registry.forgetSessions(room.getRoomId(), userId);
        }
    };

    public GameStateUpdate join(GameRoom room, String userId, GameAction action) {
        return RoomSeats.join(room, userId, action, keeper);
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
        if (RoomSeats.nobodyConnected(room)) {
            // Nobody left: the room closes (after a short wait, so a reload finds it)
            room.setEmptySince(Instant.now());
            return null;
        }
        // If it was the owner, sweepRooms passes the room on unless they come back soon (a reload)
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_DISCONNECTED", userId);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        return update;
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
        return RoomSeats.close(room, userId, keeper);
    }

    // ------------------------------------------------------------------ Owner gone, abandons

    /** An online game whose player has been disconnected a while: they left it, and the one who stays wins. */
    GameStateUpdate checkAbandoned(GameRoom room, long now) {
        if (room.isEditor() || room.isBot() || !RoomGuard.playing(room) || registry.get(room.getRoomId()) == null) return null;
        PlayerState gone = room.getPlayers().values().stream()
                .filter(p -> !p.isConnected() && now - p.getDisconnectedAt() >= SimulatorLimits.ABANDON_GRACE_MS).findFirst().orElse(null);
        if (gone == null || room.getPlayers().values().stream().noneMatch(PlayerState::isConnected)) return null;
        MissionHooks.Before before = MissionHooks.Before.of(room);
        remove(room, gone.getUserId());
        service.recorder.abandon(room, gone.getUserId(), service.history);
        GameStateUpdate update = GameStateUpdate.from(room, "PLAYER_LEFT", gone.getUserId());
        update.setLogEntry(gone.getUsername() + " abandonó la partida");
        service.recordLog(room, update);
        update.setEventLog(new ArrayList<>(room.getEventLog()));
        if (service.hooks != null) service.hooks.count(room, before);
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
        RoomSeats.passOwnership(room, previous);
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
        RoomSeats.lostToTheBot(room);
    }

    // ------------------------------------------------------------------ Owner, leaving and kicking

    /**
     * Takes a player out of the room: the owner passes to another player, an empty room closes, and a game in
     * course ends: an online one is won by the one who stays (post-game); otherwise back to the pre-room. Returns
     * whether the room was closed.
     */
    public boolean remove(GameRoom room, String userId) {
        return RoomSeats.remove(room, userId, keeper);
    }

    /** A player leaves the room for good (their seat is freed). */
    public GameStateUpdate leave(GameRoom room, String userId) {
        return RoomSeats.leave(room, userId, keeper);
    }

    /** The owner takes another player out of the room (in a game, only one who is disconnected). */
    public GameStateUpdate kick(GameRoom room, String userId, GameAction action) {
        return RoomSeats.kick(room, userId, action, keeper);
    }
}
