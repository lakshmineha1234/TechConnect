package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.*;

/**
 * Real-time chat between accepted connections.
 *
 * WebSocket:
 *   Send    /app/chat.send         { recipientId, content }
 *   Receive /user/queue/chat       { id, senderId, senderName, recipientId, content, createdAt }
 *
 * REST:
 *   GET  /api/chat/conversations          list of conversations with last msg + unread count
 *   GET  /api/chat/messages/{userId}      last 60 messages with a user
 *   PUT  /api/chat/read/{userId}          mark all messages from userId as read
 *   GET  /api/chat/unread                 total unread message count for nav badge
 */
@Controller
public class ChatController {

    private final JdbcTemplate           jdbc;
    private final SimpMessagingTemplate  messaging;

    public ChatController(JdbcTemplate jdbc, SimpMessagingTemplate messaging) {
        this.jdbc      = jdbc;
        this.messaging = messaging;
    }

    // ── WebSocket: send a message ─────────────────────────────────────────────
    @MessageMapping("/chat.send")
    public void sendMessage(@Payload Map<String, String> payload, Principal principal) {
        if (principal == null) return;

        String senderId    = principal.getName();
        String recipientId = payload.get("recipientId");
        String content     = (payload.get("content") == null ? "" : payload.get("content")).trim();

        if (recipientId == null || recipientId.isBlank() || content.isEmpty()) return;
        if (senderId.equals(recipientId)) return;

        // Only allow chat between accepted connections
        Integer connected = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM connections
                WHERE status = 'accepted'
                  AND ((requester_id = ? AND recipient_id = ?)
                    OR (requester_id = ? AND recipient_id = ?))
                """, Integer.class, senderId, recipientId, recipientId, senderId);
        if (connected == null || connected == 0) return;

        // Persist the message
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update(
                "INSERT INTO messages (id, sender_id, receiver_id, content, is_read, created_at) VALUES (?,?,?,?,0,datetime('now'))",
                id, senderId, recipientId, content);

        // Build message payload
        String senderName = getSenderName(senderId);
        Map<String, Object> msg = buildMessage(id, senderId, senderName, recipientId, content, now);

        // Push to recipient's queue
        messaging.convertAndSendToUser(recipientId, "/queue/chat", msg);
        // Echo back to sender (supports multiple tabs / devices)
        messaging.convertAndSendToUser(senderId,    "/queue/chat", msg);
    }

    // ── REST: send a message (fallback when WebSocket is unavailable) ────────
    @PostMapping("/api/chat/send")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sendRest(
            @RequestBody Map<String, String> body, HttpSession session) {

        String senderId = (String) session.getAttribute("userId");
        if (senderId == null) return ResponseEntity.status(401).build();

        String recipientId = body.get("recipientId");
        String content     = (body.getOrDefault("content", "")).trim();

        if (recipientId == null || recipientId.isBlank() || content.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid payload."));
        if (senderId.equals(recipientId))
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot message yourself."));

        // Only allow chat between accepted connections
        Integer connected = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM connections
                WHERE status = 'accepted'
                  AND ((requester_id = ? AND recipient_id = ?)
                    OR (requester_id = ? AND recipient_id = ?))
                """, Integer.class, senderId, recipientId, recipientId, senderId);
        if (connected == null || connected == 0)
            return ResponseEntity.status(403).body(Map.of("error", "You are not connected with this user."));

        // Persist
        String id  = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update(
                "INSERT INTO messages (id, sender_id, receiver_id, content, is_read, created_at) VALUES (?,?,?,?,0,datetime('now'))",
                id, senderId, recipientId, content);

        String senderName = getSenderName(senderId);
        Map<String, Object> msg = buildMessage(id, senderId, senderName, recipientId, content, now);

        // Push via STOMP so the recipient (and other tabs) get it in real-time
        try {
            messaging.convertAndSendToUser(recipientId, "/queue/chat", msg);
            messaging.convertAndSendToUser(senderId,    "/queue/chat", msg);
        } catch (Exception ignored) { /* broker unavailable – message is already persisted */ }

        return ResponseEntity.ok(msg);
    }

    // ── REST: get all conversations ───────────────────────────────────────────
    @GetMapping("/api/chat/conversations")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> conversations(HttpSession session) {
        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        // All messages involving me, newest first
        List<Map<String, Object>> allMsgs = jdbc.queryForList(
                """
                SELECT m.id, m.sender_id, m.receiver_id, m.content, m.is_read, m.created_at,
                       u.first_name, u.last_name, u.role
                FROM messages m
                JOIN users u ON u.id = CASE WHEN m.sender_id = ? THEN m.receiver_id ELSE m.sender_id END
                WHERE m.sender_id = ? OR m.receiver_id = ?
                ORDER BY m.created_at DESC
                """, me, me, me);

        // Group by partner, keep only latest message per partner
        Map<String, Map<String, Object>> byPartner = new LinkedHashMap<>();
        Map<String, Integer> unreadCounts = new HashMap<>();

        for (Map<String, Object> m : allMsgs) {
            String senderId    = (String) m.get("sender_id");
            String receiverId  = (String) m.get("receiver_id");
            String partnerId   = me.equals(senderId) ? receiverId : senderId;

            if (!byPartner.containsKey(partnerId)) {
                String fn   = s(m, "first_name");
                String ln   = s(m, "last_name");
                Map<String, Object> conv = new LinkedHashMap<>();
                conv.put("userId",      partnerId);
                conv.put("name",        (fn + " " + ln).trim());
                conv.put("firstName",   fn);
                conv.put("lastName",    ln);
                conv.put("role",        m.get("role"));
                conv.put("lastMessage", s(m, "content"));
                conv.put("lastTime",    s(m, "created_at"));
                conv.put("isMyMessage", me.equals(senderId));
                byPartner.put(partnerId, conv);
            }

            // Count unread messages sent by partner to me
            if (!me.equals(senderId) && Integer.valueOf(0).equals(toInt(m.get("is_read")))) {
                unreadCounts.merge(partnerId, 1, Integer::sum);
            }
        }

        // Attach unread counts
        byPartner.forEach((pid, conv) ->
                conv.put("unread", unreadCounts.getOrDefault(pid, 0)));

        return ResponseEntity.ok(new ArrayList<>(byPartner.values()));
    }

    // ── REST: get message history with one user ───────────────────────────────
    @GetMapping("/api/chat/messages/{userId}")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> history(
            @PathVariable String userId, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id, sender_id, receiver_id, content, is_read, created_at
                FROM messages
                WHERE (sender_id = ? AND receiver_id = ?)
                   OR (sender_id = ? AND receiver_id = ?)
                ORDER BY created_at ASC
                LIMIT 60
                """, me, userId, userId, me);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",         r.get("id"));
            m.put("senderId",   r.get("sender_id"));
            m.put("content",    r.get("content"));
            m.put("isRead",     r.get("is_read"));
            m.put("createdAt",  r.get("created_at"));
            m.put("mine",       me.equals(r.get("sender_id")));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── REST: mark all messages from a user as read ───────────────────────────
    @PutMapping("/api/chat/read/{userId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> markRead(
            @PathVariable String userId, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        int rows = jdbc.update(
                "UPDATE messages SET is_read = 1 WHERE sender_id = ? AND receiver_id = ? AND is_read = 0",
                userId, me);
        return ResponseEntity.ok(Map.of("marked", rows));
    }

    // ── REST: total unread count (for nav badge) ──────────────────────────────
    @GetMapping("/api/chat/unread")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unreadCount(HttpSession session) {
        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE receiver_id = ? AND is_read = 0",
                Integer.class, me);
        return ResponseEntity.ok(Map.of("unread", count == null ? 0 : count));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private String getSenderName(String userId) {
        try {
            Map<String, Object> u = jdbc.queryForMap(
                    "SELECT first_name, last_name FROM users WHERE id = ?", userId);
            return ((String) u.get("first_name") + " " + u.get("last_name")).trim();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private Map<String, Object> buildMessage(String id, String senderId, String senderName,
                                              String recipientId, String content, String createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          id);
        m.put("senderId",    senderId);
        m.put("senderName",  senderName);
        m.put("recipientId", recipientId);
        m.put("content",     content);
        m.put("createdAt",   createdAt);
        return m;
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }

    private static Integer toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Integer i) return i;
        return Integer.parseInt(v.toString());
    }
}
