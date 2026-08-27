package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * POST /api/users/{id}/mute   toggle mute — returns {muted, mutedId}
 * GET  /api/users/muted       list of users I've muted {id, name, role, jobTitle}
 */
@RestController
@RequestMapping("/api/users")
public class MuteController {

    private final JdbcTemplate jdbc;

    public MuteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/{id}/mute")
    public ResponseEntity<Map<String, Object>> toggleMute(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();
        if (me.equals(id)) return ResponseEntity.badRequest().body(Map.of("error", "Cannot mute yourself."));

        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM muted_users WHERE user_id=? AND muted_id=?",
            Integer.class, me, id);

        boolean nowMuted;
        if (exists != null && exists > 0) {
            jdbc.update("DELETE FROM muted_users WHERE user_id=? AND muted_id=?", me, id);
            nowMuted = false;
        } else {
            jdbc.update("INSERT OR IGNORE INTO muted_users (user_id, muted_id) VALUES (?,?)", me, id);
            nowMuted = true;
        }
        return ResponseEntity.ok(Map.of("muted", nowMuted, "mutedId", id));
    }

    @GetMapping("/muted")
    public ResponseEntity<List<Map<String, Object>>> listMuted(HttpSession session) {
        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.first_name || ' ' || u.last_name AS name, u.role,
                   COALESCE(p.job_title,'') AS jobTitle, COALESCE(p.institution,'') AS institution
            FROM muted_users m
            JOIN users u ON u.id = m.muted_id
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE m.user_id = ?
            ORDER BY m.created_at DESC
            """, me);

        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          r.get("id"));
            m.put("name",        r.get("name"));
            m.put("role",        r.get("role"));
            m.put("jobTitle",    r.get("jobTitle"));
            m.put("institution", r.get("institution"));
            return m;
        }).collect(Collectors.toList()));
    }
}
