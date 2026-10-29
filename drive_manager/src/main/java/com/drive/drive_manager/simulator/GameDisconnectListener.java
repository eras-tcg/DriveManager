package com.drive.drive_manager.simulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class GameDisconnectListener implements ApplicationListener<SessionDisconnectEvent> {

    @Autowired
    private RoomService roomService;

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) {
        roomService.handleSessionDisconnect(event.getSessionId());
    }
}
