package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.techconnect.controller.AuthController.err;

@RestController
@RequestMapping("/api/users")
public class BlockController {

    private final JdbcTemplate jdbc;

    public BlockController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // POST /api/users/{id}/block — toggle block
    @PostMapping("/{id}/block")
    public ResponseEntity<Map<String, Object>> toggleBlock(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");
        if (uid.equals(id)) return err(400, "Cannot block yourself.");

        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM blocked_users WHERE blocker_id=? AND blocked_id=?",
            Integer.class, uid, id);

        boolean nowBlocked;
        if (already != null && already > 0) {
            jdbc.update("DELETE FROM blocked_users WHERE blocker_id=? AND blocked_id=?", uid, id);
            nowBlocked = false;
        } else {
            jdbc.update("INSERT INTO blocked_users (blocker_id, blocked_id) VALUES (?,?) ON CONFLICT DO NOTHING", uid, id);
            nowBlocked = true;
            // Also remove any pending connection between them
            jdbc.update("""
                DELETE FROM connections
                WHERE (requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?)
                """, uid, id, id, uid);
        }

        return ResponseEntity.ok(Map.of("blocked", nowBlocked, "blockedId", id));
    }

    // GET /api/users/blocked — list users I have blocked
    @GetMapping("/blocked")
    public ResponseEntity<List<Map<String, Object>>> listBlocked(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.first_name || ' ' || u.last_name AS name,
                   u.role, p.job_title, p.institution
            FROM blocked_users b
            JOIN users u ON u.id = b.blocked_id
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE b.blocker_id = ?
            ORDER BY b.created_at DESC
            """, uid);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          r.get("id"));
            m.put("name",        r.get("name"));
            m.put("role",        r.get("role"));
            m.put("jobTitle",    r.getOrDefault("job_title",  ""));
            m.put("institution", r.getOrDefault("institution",""));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }
}
