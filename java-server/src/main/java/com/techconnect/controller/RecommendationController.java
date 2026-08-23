package com.techconnect.controller;

import com.techconnect.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Written recommendations between connected users.
 *
 * POST   /api/recommendations                    write a rec (must be connected, one per pair)
 * GET    /api/recommendations/{userId}            approved recs for a profile
 * GET    /api/recommendations/pending             pending recs awaiting my approval
 * PUT    /api/recommendations/{id}/approve        approve a pending rec
 * PUT    /api/recommendations/{id}/hide           hide an approved rec
 * DELETE /api/recommendations/{id}               author deletes their own rec
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final JdbcTemplate jdbc;
    private final NotificationService notifSvc;

    public RecommendationController(JdbcTemplate jdbc, NotificationService notifSvc) {
        this.jdbc = jdbc;
        this.notifSvc = notifSvc;
    }

    // ── Write ─────────────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> write(
            @RequestBody Map<String, Object> body, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        String recipientId = s(body, "recipientId");
        String text        = s(body, "text").strip();

        if (recipientId.isEmpty()) return err(400, "recipientId required.");
        if (me.equals(recipientId))  return err(400, "Cannot recommend yourself.");
        if (text.isEmpty())          return err(400, "Recommendation text cannot be empty.");
        if (text.length() > 600)     return err(400, "Too long (max 600 characters).");

        // Must be connected
        Integer conn = jdbc.queryForObject(
            "SELECT COUNT(*) FROM connections WHERE status='accepted' AND " +
            "((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))",
            Integer.class, me, recipientId, recipientId, me);
        if (conn == null || conn == 0)
            return err(403, "You must be connected to recommend this person.");

        // One recommendation per author-recipient pair
        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM recommendations WHERE author_id=? AND recipient_id=?",
            Integer.class, me, recipientId);
        if (exists != null && exists > 0)
            return err(409, "You have already written a recommendation for this person.");

        String id = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO recommendations (id, author_id, recipient_id, text) VALUES (?,?,?,?)",
            id, me, recipientId, text);

        notifSvc.create(recipientId, "recommendation_received", me, id);

        return ResponseEntity.ok(Map.of("id", id, "status", "pending"));
    }

    // ── Get approved recs for a profile ──────────────────────────────────────
    @GetMapping("/{userId}")
    public ResponseEntity<List<Map<String, Object>>> forUser(
            @PathVariable String userId, HttpSession session) {

        if (session.getAttribute("userId") == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT r.id, r.author_id, r.text, r.created_at,
                   u.first_name, u.last_name, u.role, p.job_title, p.company
            FROM recommendations r
            JOIN users u ON u.id = r.author_id
            LEFT JOIN profiles p ON p.user_id = r.author_id
            WHERE r.recipient_id = ? AND r.status = 'approved'
            ORDER BY r.created_at DESC
            """, userId);

        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",        r.get("id"));
            m.put("authorId",  r.get("author_id"));
            m.put("authorName",(s(r,"first_name") + " " + s(r,"last_name")).trim());
            m.put("authorRole", r.get("role"));
            m.put("authorTitle", s(r,"job_title").isEmpty() ? s(r,"company") : s(r,"job_title"));
            m.put("text",      r.get("text"));
            m.put("createdAt", r.get("created_at"));
            return m;
        }).collect(Collectors.toList()));
    }

    // ── Pending recs awaiting my approval ─────────────────────────────────────
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> pending(HttpSession session) {
        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT r.id, r.author_id, r.text, r.created_at,
                   u.first_name, u.last_name, u.role, p.job_title
            FROM recommendations r
            JOIN users u ON u.id = r.author_id
            LEFT JOIN profiles p ON p.user_id = r.author_id
            WHERE r.recipient_id = ? AND r.status = 'pending'
            ORDER BY r.created_at DESC
            """, me);

        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",         r.get("id"));
            m.put("authorId",   r.get("author_id"));
            m.put("authorName", (s(r,"first_name") + " " + s(r,"last_name")).trim());
            m.put("authorTitle", s(r,"job_title"));
            m.put("text",       r.get("text"));
            m.put("createdAt",  r.get("created_at"));
            return m;
        }).collect(Collectors.toList()));
    }

    // ── Approve ───────────────────────────────────────────────────────────────
    @PutMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        int updated = jdbc.update(
            "UPDATE recommendations SET status='approved' WHERE id=? AND recipient_id=? AND status='pending'",
            id, me);
        if (updated == 0) return err(404, "Recommendation not found or already processed.");

        // Notify author that their rec was approved
        try {
            String authorId = jdbc.queryForObject(
                "SELECT author_id FROM recommendations WHERE id=?", String.class, id);
            notifSvc.create(authorId, "recommendation_approved", me, id);
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Hide (recipient hides an approved rec) ────────────────────────────────
    @PutMapping("/{id}/hide")
    public ResponseEntity<Map<String, Object>> hide(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        int updated = jdbc.update(
            "UPDATE recommendations SET status='hidden' WHERE id=? AND recipient_id=?", id, me);
        if (updated == 0) return err(404, "Recommendation not found.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Delete (author removes their own rec) ─────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        int deleted = jdbc.update(
            "DELETE FROM recommendations WHERE id=? AND author_id=?", id, me);
        if (deleted == 0) return err(404, "Recommendation not found or not yours.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String s(Map<?, ?> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
