package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.techconnect.controller.AuthController.err;

@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final JdbcTemplate jdbc;

    public DraftController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // GET /api/drafts — load saved draft
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDraft(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT content, updated_at FROM post_drafts WHERE user_id=?", uid);

        if (rows.isEmpty()) return ResponseEntity.ok(Map.of("content", "", "updatedAt", ""));
        Map<String, Object> row = rows.get(0);
        return ResponseEntity.ok(Map.of(
            "content",   row.getOrDefault("content",    ""),
            "updatedAt", row.getOrDefault("updated_at", "")
        ));
    }

    // POST /api/drafts — upsert draft
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveDraft(
            @RequestBody Map<String, Object> body, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String content = body == null ? "" : body.getOrDefault("content", "").toString();
        if (content.length() > 1000) content = content.substring(0, 1000);

        jdbc.update("""
            INSERT INTO post_drafts (user_id, content, updated_at)
            VALUES (?, ?, datetime('now'))
            ON CONFLICT(user_id) DO UPDATE SET content=excluded.content, updated_at=excluded.updated_at
            """, uid, content);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    // DELETE /api/drafts — discard draft
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteDraft(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");
        jdbc.update("DELETE FROM post_drafts WHERE user_id=?", uid);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
