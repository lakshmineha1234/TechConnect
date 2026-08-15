package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * POST /api/profile-views/{userId}   — record a profile view (no self-views, one per viewer/day)
 * GET  /api/profile-views/me         — my view stats: total count + recent unique viewers
 * GET  /api/profile-views/me/count   — just the total count (for dashboard badge)
 */
@RestController
@RequestMapping("/api/profile-views")
public class ProfileViewController {

    private final JdbcTemplate jdbc;

    public ProfileViewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/{viewedId}")
    public ResponseEntity<Map<String, Object>> record(
            @PathVariable String viewedId,
            HttpSession session) {

        String viewerId = (String) session.getAttribute("userId");
        if (viewerId == null) return ResponseEntity.status(401).build();
        if (viewerId.equals(viewedId)) return ResponseEntity.ok(Map.of("ok", true)); // no self-views

        try {
            String id = UUID.randomUUID().toString();
            // UNIQUE(viewer_id, viewed_id, view_date) — one entry per viewer per day
            jdbc.update("""
                INSERT OR IGNORE INTO profile_views (id, viewer_id, viewed_id, view_date)
                VALUES (?, ?, ?, date('now'))
                """, id, viewerId, viewedId);
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me/count")
    public ResponseEntity<Map<String, Object>> count(HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).build();

        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM profile_views WHERE viewed_id = ?",
            Integer.class, userId);

        return ResponseEntity.ok(Map.of("count", total == null ? 0 : total));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> myViews(HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).build();

        // Total view count
        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM profile_views WHERE viewed_id = ?",
            Integer.class, userId);

        // Last 30 days count
        Integer last30 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM profile_views WHERE viewed_id = ? AND viewed_at >= datetime('now','-30 days')",
            Integer.class, userId);

        // Most recent 20 unique viewers (latest visit per viewer)
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.first_name || ' ' || u.last_name AS name, u.role,
                   COALESCE(p.job_title,'') AS jobTitle, COALESCE(p.company,'') AS company,
                   COALESCE(p.institution,'') AS institution,
                   MAX(pv.viewed_at) AS lastViewed
            FROM profile_views pv
            JOIN users u ON u.id = pv.viewer_id
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE pv.viewed_id = ?
            GROUP BY pv.viewer_id
            ORDER BY lastViewed DESC
            LIMIT 20
            """, userId);

        List<Map<String, Object>> viewers = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id",          r.get("id"));
            v.put("name",        r.get("name"));
            v.put("role",        r.get("role"));
            v.put("jobTitle",    r.get("jobTitle"));
            v.put("company",     r.get("company"));
            v.put("institution", r.get("institution"));
            v.put("lastViewed",  r.get("lastViewed"));
            viewers.add(v);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",   total   == null ? 0 : total);
        result.put("last30",  last30  == null ? 0 : last30);
        result.put("viewers", viewers);
        return ResponseEntity.ok(result);
    }
}
