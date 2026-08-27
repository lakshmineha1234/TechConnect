package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.techconnect.controller.AuthController.err;

@RestController
public class ReportController {

    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // POST /api/posts/{id}/report
    @PostMapping("/api/posts/{id}/report")
    public ResponseEntity<Map<String, Object>> reportPost(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        // Verify post exists
        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id=?", Integer.class, id);
        if (exists == null || exists == 0) return err(404, "Post not found.");

        // Cannot report own post
        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id=? AND user_id=?", Integer.class, id, uid);
        if (owns != null && owns > 0) return err(400, "Cannot report your own post.");

        String reason = body == null ? "" : body.getOrDefault("reason", "").toString().trim();
        if (reason.length() > 200) reason = reason.substring(0, 200);

        String reportId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                INSERT INTO post_reports (id, post_id, reporter_id, reason)
                VALUES (?, ?, ?, ?)
                """, reportId, id, uid, reason);
        } catch (Exception e) {
            // UNIQUE constraint — already reported
            return err(409, "You have already reported this post.");
        }

        return ResponseEntity.ok(Map.of("ok", true, "message", "Report submitted. Thank you for helping keep TechConnect safe."));
    }

    // GET /api/admin/reports  — basic list (any authenticated user can view for now; production would gate by role)
    @GetMapping("/api/admin/reports")
    public ResponseEntity<?> listReports(
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT r.id, r.post_id, r.reason, r.created_at,
                   u.first_name || ' ' || u.last_name AS reporter_name,
                   p.content AS post_content,
                   pu.first_name || ' ' || pu.last_name AS post_author_name
            FROM post_reports r
            JOIN users u  ON u.id  = r.reporter_id
            JOIN posts p  ON p.id  = r.post_id
            JOIN users pu ON pu.id = p.user_id
            ORDER BY r.created_at DESC
            LIMIT 50 OFFSET ?
            """, offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",             row.get("id"));
            m.put("postId",         row.get("post_id"));
            m.put("reason",         row.get("reason"));
            m.put("createdAt",      row.get("created_at"));
            m.put("reporterName",   row.get("reporter_name"));
            m.put("postContent",    ((String) row.getOrDefault("post_content", "")).substring(
                                        0, Math.min(120, ((String) row.getOrDefault("post_content", "")).length())));
            m.put("postAuthorName", row.get("post_author_name"));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }
}
