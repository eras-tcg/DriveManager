package com.drive.drive_manager.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SessionCheck sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // No session, no socket: an anonymous client could otherwise listen to any room
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            String token = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : null;
            if (token == null || !jwtUtils.isValid(token) || !sessions.isCurrent(jwtUtils.extractId(token), jwtUtils.extractTokenVersion(token)))
                throw new MessageDeliveryException("Sesión inválida");
            List<SimpleGrantedAuthority> authorities = jwtUtils.extractPermissions(token).stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            accessor.setUser(new UsernamePasswordAuthenticationToken(jwtUtils.extractId(token), null, authorities));
        }
        // Only one's own queue can be listened to (Spring resolves /user/... to this session's user)
        if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith("/user/queue/room/")) throw new MessageDeliveryException("Destino no permitido");
        }
        return message;
    }
}
