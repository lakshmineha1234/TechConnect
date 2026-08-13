package com.techconnect.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which users are currently connected via WebSocket.
 * Broadcasts status changes to /topic/online-status so all clients
 * can update their green-dot indicators in real time.
 */
@Service
public class OnlineStatusService {

    private final SimpMessagingTemplate ws;
    private final Set<String> onlineUsers = ConcurrentHashMap.newKeySet();

    public OnlineStatusService(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    public void userConnected(String userId) {
        if (userId == null) return;
        onlineUsers.add(userId);
        broadcast(userId, true);
    }

    public void userDisconnected(String userId) {
        if (userId == null) return;
        onlineUsers.remove(userId);
        broadcast(userId, false);
    }

    public Set<String> getOnlineUsers() {
        return onlineUsers;
    }

    public boolean isOnline(String userId) {
        return userId != null && onlineUsers.contains(userId);
    }

    private void broadcast(String userId, boolean online) {
        try {
            ws.convertAndSend("/topic/online-status",
                Map.of("userId", userId, "online", online));
        } catch (Exception ignored) {}
    }
}
