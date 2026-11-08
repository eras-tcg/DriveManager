package com.drive.drive_manager.simulator.room;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.Set;

/** The server sending each player their own view of a room ({@link StateMask}) over the WebSocket. */
final class RoomMessages {

    private RoomMessages() {}

    /**
     * Sends each player their own view of the room on their queue (/user/queue/room/{id}). An error goes only to
     * who caused it; a player who just left or was taken out still gets the update that says so. {@code room} is
     * null once the room is closed: its last update still names who was in it.
     */
    static void send(SimpMessagingTemplate template, String roomId, GameRoom room, GameStateUpdate update) {
        send(template, null, roomId, room, update);
    }

    /** {@code users}: who is connected, and from how many tabs (null: leave it to Spring). */
    static void send(SimpMessagingTemplate template, SimpUserRegistry users, String roomId, GameRoom room,
                     GameStateUpdate update) {
        if (update == null || template == null) return;
        String destination = "/queue/room/" + roomId;
        // Who gets it is the room session's rule (StateMask.recipients): an error only who caused it, as it is
        for (String viewer : StateMask.recipients(room, update)) {
            if (update.getError() != null) { toUser(template, users, viewer, destination, update); continue; }
            boolean manual = room != null && room.getManualPlayers().contains(viewer);
            toUser(template, users, viewer, destination, StateMask.forViewer(update, viewer, manual));
        }
    }

    /**
     * Sends to each of the user's sessions (tabs) on its own. With setPreserveReceiveOrder(true), Spring 7.0.5 sends
     * one user message to all their sessions as the same message, which the first send leaves immutable: every other
     * session got "Expected mutable SimpMessageHeaderAccessor" and no update. A message with a session id goes only to
     * that session, so each tab gets its own.
     */
    static void toUser(SimpMessagingTemplate template, SimpUserRegistry users, String userId, String destination,
                       Object payload) {
        SimpUser user = users == null ? null : users.getUser(userId);
        Set<SimpSession> sessions = user == null ? Set.of() : user.getSessions();
        if (sessions.size() < 2) {
            template.convertAndSendToUser(userId, destination, payload);
            return;
        }
        for (SimpSession session : sessions) {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId(session.getId());
            headers.setLeaveMutable(true);
            template.convertAndSendToUser(userId, destination, payload, headers.getMessageHeaders());
        }
    }
}
