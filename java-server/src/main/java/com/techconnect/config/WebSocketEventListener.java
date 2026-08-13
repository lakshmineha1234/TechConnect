package com.techconnect.config;

import com.techconnect.service.OnlineStatusService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Component
public class WebSocketEventListener {

    private final OnlineStatusService onlineStatus;

    public WebSocketEventListener(OnlineStatusService onlineStatus) {
        this.onlineStatus = onlineStatus;
    }

    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        String userId = extractUserId(StompHeaderAccessor.wrap(event.getMessage()));
        onlineStatus.userConnected(userId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String userId = extractUserId(StompHeaderAccessor.wrap(event.getMessage()));
        onlineStatus.userDisconnected(userId);
    }

    private String extractUserId(StompHeaderAccessor accessor) {
        // Principal was set in WebSocketConfig ChannelInterceptor
        if (accessor.getUser() != null) return accessor.getUser().getName();
        // Fallback: read from session attributes
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) return (String) attrs.get("userId");
        return null;
    }
}
