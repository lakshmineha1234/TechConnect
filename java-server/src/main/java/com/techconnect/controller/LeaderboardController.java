package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * GET /api/leaderboard
 * Returns community-wide stats:
 *  - topConnected  : top 5 users by accepted connection count
 *  - topPosters    : top 5 users by post count (all time)
 *  - topSkills     : top 5 skills by total endorsement count
 *  - stats         : { totalUsers, totalPosts, totalConnections, totalEndorsements }
 */
@RestController
public class LeaderboardController {

    private final JdbcTemplate jdbc;

    public LeaderboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/leaderboard")
    public ResponseEntity<Map<String, Object>> leaderboard(HttpSession session) {
        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        // Top 5 most connected
        List<Map<String, Object>> connRows = jdbc.queryForList("""
            SELECT u.id, u.first_name, u.last_name, u.role,
                   COUNT(*) AS conn_count
            FROM connections c
            JOIN users u ON u.id = CASE WHEN c.requester_id = u.id THEN c.requester_id ELSE c.recipient_id END
            WHERE c.status = 'accepted'
              AND (u.id = c.requester_id OR u.id = c.recipient_id)
            GROUP BY u.id
            ORDER BY conn_count DESC
            LIMIT 5
            """);

        List<Map<String, Object>> topConnected = new ArrayList<>();
        for (Map<String, Object> r : connRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",   r.get("id"));
            item.put("name", (s(r,"first_name") + " " + s(r,"last_name")).trim());
            item.put("role", s(r,"role"));
            item.put("count", toLong(r.get("conn_count")));
            topConnected.add(item);
        }

        // Top 5 most active posters
        List<Map<String, Object>> posterRows = jdbc.queryForList("""
            SELECT u.id, u.first_name, u.last_name, u.role,
                   COUNT(p.id) AS post_count
            FROM posts p
            JOIN users u ON u.id = p.user_id
            WHERE p.shared_from_id = ''
            GROUP BY u.id
            ORDER BY post_count DESC
            LIMIT 5
            """);

        List<Map<String, Object>> topPosters = new ArrayList<>();
        for (Map<String, Object> r : posterRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",   r.get("id"));
            item.put("name", (s(r,"first_name") + " " + s(r,"last_name")).trim());
            item.put("role", s(r,"role"));
            item.put("count", toLong(r.get("post_count")));
            topPosters.add(item);
        }

        // Top 5 endorsed skills
        List<Map<String, Object>> skillRows = jdbc.queryForList("""
            SELECT skill_name, COUNT(*) AS total
            FROM skill_endorsements
            GROUP BY skill_name
            ORDER BY total DESC
            LIMIT 5
            """);

        List<Map<String, Object>> topSkills = new ArrayList<>();
        for (Map<String, Object> r : skillRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skill", s(r,"skill_name"));
            item.put("count", toLong(r.get("total")));
            topSkills.add(item);
        }

        // Community-wide stats
        long totalUsers       = q("SELECT COUNT(*) FROM users");
        long totalPosts       = q("SELECT COUNT(*) FROM posts WHERE shared_from_id = ''");
        long totalConnections = q("SELECT COUNT(*) FROM connections WHERE status = 'accepted'");
        long totalEndorse     = q("SELECT COUNT(*) FROM skill_endorsements");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers",        totalUsers);
        stats.put("totalPosts",        totalPosts);
        stats.put("totalConnections",  totalConnections);
        stats.put("totalEndorsements", totalEndorse);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topConnected",  topConnected);
        result.put("topPosters",    topPosters);
        result.put("topSkills",     topSkills);
        result.put("stats",         stats);
        return ResponseEntity.ok(result);
    }

    private long q(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }
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
