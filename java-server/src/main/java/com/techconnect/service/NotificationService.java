package com.techconnect.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Centralized helper for writing notification rows.
 * Injected into controllers that need to fire notifications.
 *
 * Types used:
 *   connection_request  — someone sent you a connection request   (ref_id = connection id)
 *   connection_accepted — your request was accepted               (ref_id = connection id)
 *   post_like           — someone liked your post                 (ref_id = post id)
 *   message             — someone sent you a message              (ref_id = sender user id)
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Create a single notification row.
     * Silently swallows errors so callers are never broken by notification failures.
     */
    public void create(String userId, String type, String actorId, String refId) {
        if (userId == null || actorId == null || userId.equals(actorId)) return;
        try {
            jdbc.update(
                "INSERT INTO notifications (id, user_id, type, actor_id, ref_id) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, type, actorId, refId == null ? "" : refId
            );
        } catch (Exception ignored) {}
    }
}
