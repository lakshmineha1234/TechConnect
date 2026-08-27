package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * GET /api/connections/{subjectId}/note   — fetch my private note about a connection
 * PUT /api/connections/{subjectId}/note   — save/update note (max 500 chars)
 *
 * Notes are only visible to the author. Only accepted connections can leave notes.
 */
@RestController
@RequestMapping("/api/connections/{subjectId}/note")
public class ConnectionNoteController {

    private final JdbcTemplate jdbc;

    public ConnectionNoteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String subjectId, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        String note = jdbc.queryForList(
            "SELECT note FROM connection_notes WHERE author_id=? AND subject_id=?",
            me, subjectId)
            .stream().map(r -> r.get("note") == null ? "" : r.get("note").toString())
            .findFirst().orElse("");

        return ResponseEntity.ok(Map.of("note", note));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable String subjectId,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();
        if (me.equals(subjectId)) return ResponseEntity.badRequest().body(Map.of("error", "Cannot note yourself."));

        // Must be accepted connection
        Integer conn = jdbc.queryForObject("""
            SELECT COUNT(*) FROM connections
            WHERE status='accepted'
              AND ((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))
            """, Integer.class, me, subjectId, subjectId, me);
        if (conn == null || conn == 0)
            return ResponseEntity.status(403).body(Map.of("error", "Not a connection."));

        String note = body.getOrDefault("note", "").toString().trim();
        if (note.length() > 500) note = note.substring(0, 500);

        jdbc.update("""
            INSERT INTO connection_notes (author_id, subject_id, note, updated_at)
            VALUES (?, ?, ?, NOW())
            ON CONFLICT(author_id, subject_id) DO UPDATE SET note=excluded.note, updated_at=NOW()
            """, me, subjectId, note);

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
