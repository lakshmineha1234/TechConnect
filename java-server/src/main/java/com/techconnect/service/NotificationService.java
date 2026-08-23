package com.techconnect.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Centralized helper for writing notification rows and pushing them in real-time.
 *
 * Types:
 *   connection_request  — ref_id = connection id
 *   connection_accepted — ref_id = connection id
 *   post_like           — ref_id = post id
 *   post_comment        — ref_id = post id
 *   meeting_request     — ref_id = meeting id
 *   meeting_accepted    — ref_id = meeting id
 *   meeting_declined    — ref_id = meeting id
 *   skill_endorsement   — ref_id = skill name
 *   job_application     — ref_id = job id
 *   application_status  — ref_id = job id
 */
@Service
public class NotificationService {

    private final JdbcTemplate           jdbc;
    private final SimpMessagingTemplate  messaging;

    public NotificationService(JdbcTemplate jdbc, SimpMessagingTemplate messaging) {
        this.jdbc      = jdbc;
        this.messaging = messaging;
    }

    /**
     * Persist a notification row and immediately push it to the recipient via WebSocket.
     * Silently swallows errors so callers are never broken by notification failures.
     */
    public void create(String userId, String type, String actorId, String refId) {
        if (userId == null || actorId == null || userId.equals(actorId)) return;
        try {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                "INSERT INTO notifications (id, user_id, type, actor_id, ref_id) VALUES (?,?,?,?,?)",
                id, userId, type, actorId, refId == null ? "" : refId
            );

            // Resolve actor name for the push payload
            String actorName = "";
            try {
                Map<String, Object> u = jdbc.queryForMap(
                    "SELECT first_name, last_name FROM users WHERE id = ?", actorId);
                actorName = (u.get("first_name") + " " + u.get("last_name")).toString().trim();
            } catch (Exception ignored) {}

            // Push to recipient's personal WebSocket queue
            messaging.convertAndSendToUser(userId, "/queue/notifications",
                Map.of("id",        id,
                       "type",      type,
                       "actorId",   actorId,
                       "actorName", actorName,
                       "refId",     refId == null ? "" : refId,
                       "isRead",    false));
        } catch (Exception ignored) {}
    }
}
