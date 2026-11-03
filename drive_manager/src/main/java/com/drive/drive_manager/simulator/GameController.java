package com.drive.drive_manager.simulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Set;

@Controller("simulatorGameController")
public class GameController {

    /** The site's editors (the permissions of its edit mode) may still move cards by hand in an online game. */
    private static final Set<String> MANUAL_PERMISSIONS = Set.of("manage_home", "manage_editions", "manage_cards");

    @Autowired
    private RoomService roomService;

    @MessageMapping("/room/{roomId}/action")
    public void handleAction(@DestinationVariable String roomId,
                              GameAction action,
                              Principal principal,
                              @Header("simpSessionId") String sessionId) {
        if (principal == null) return;
        String userId = principal.getName();

        // Track which STOMP session belongs to which room/user so a
        // SessionDisconnectEvent (browser closed / tab killed) can be
        // resolved back to a player.
        roomService.registerSession(sessionId, roomId, userId);

        boolean manual = principal instanceof Authentication auth
                && auth.getAuthorities().stream().anyMatch(a -> MANUAL_PERMISSIONS.contains(a.getAuthority()));
        roomService.applyAndBroadcast(roomId, userId, action, manual);
    }
}
