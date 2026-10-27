package com.drive.drive_manager.simulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller("simulatorGameController")
public class GameController {

    @Autowired
    private RoomService roomService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

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

        GameStateUpdate update = roomService.applyAction(roomId, userId, action);
        messagingTemplate.convertAndSend("/topic/room/" + roomId, update);
    }
}
