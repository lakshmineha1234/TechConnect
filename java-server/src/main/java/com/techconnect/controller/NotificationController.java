package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Notification REST API — no class-level @RequestMapping (avoids StaticController conflict).
 *
 * GET  /api/notifications              list recent 30 for current user
 * GET  /api/notifications/unread-count { count: N }
 * POST /api/notifications/read-all     mark all as read
 * POST /api/notifications/{id}/read    mark one as read
 */
@RestController
public class NotificationController {

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── List notifications ────────────────────────────────────────────────────
    @GetMapping("/api/notifications")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> list(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT n.id, n.type, n.actor_id, n.ref_id, n.is_read, n.created_at,
                       u.first_name, u.last_name
                FROM notifications n
                JOIN users u ON u.id = n.actor_id
                WHERE n.user_id = ?
                ORDER BY n.created_at DESC
                LIMIT 30
                """, uid);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id",        r.get("id"));
            n.put("type",      s(r, "type"));
            n.put("actorId",   s(r, "actor_id"));
            n.put("actorName", (s(r, "first_name") + " " + s(r, "last_name")).trim());
            n.put("refId",     s(r, "ref_id"));
            n.put("isRead",    toLong(r.get("is_read")) > 0);
            n.put("createdAt", s(r, "created_at"));
            result.add(n);
        }
        return ResponseEntity.ok(result);
    }

    // ── Unread count ──────────────────────────────────────────────────────────
    @GetMapping("/api/notifications/unread-count")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unreadCount(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0",
                Long.class, uid);
        return ResponseEntity.ok(Map.of("count", count == null ? 0 : count));
    }

    // ── Mark all read ─────────────────────────────────────────────────────────
    @PostMapping("/api/notifications/read-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> markAllRead(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        jdbc.update("UPDATE notifications SET is_read = 1 WHERE user_id = ?", uid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Mark one read ─────────────────────────────────────────────────────────
    @PostMapping("/api/notifications/{id}/read")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> markOneRead(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        jdbc.update("UPDATE notifications SET is_read = 1 WHERE id = ? AND user_id = ?", id, uid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
}
