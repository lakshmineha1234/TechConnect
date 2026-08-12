package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * GET /api/users/new-members   — recently joined users with full profile info
 */
@RestController
public class NewMemberController {

    private final JdbcTemplate jdbc;

    public NewMemberController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/users/new-members")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> newMembers(
            @RequestParam(defaultValue = "20") int limit,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.first_name, u.last_name, u.role, u.created_at,
                   p.bio, p.location, p.institution, p.company, p.job_title, p.degree, p.year,
                   (SELECT GROUP_CONCAT(skill_name, ',') FROM skills WHERE user_id = u.id) AS skills,
                   (SELECT COUNT(*) FROM connections
                    WHERE status = 'accepted'
                      AND (requester_id = u.id OR recipient_id = u.id))                    AS connection_count,
                   (SELECT COUNT(*) FROM connections
                    WHERE status = 'pending'
                      AND ((requester_id = ? AND recipient_id = u.id)
                        OR (requester_id = u.id AND recipient_id = ?)))                    AS pending_with_me,
                   (SELECT COUNT(*) FROM connections
                    WHERE status = 'accepted'
                      AND ((requester_id = ? AND recipient_id = u.id)
                        OR (requester_id = u.id AND recipient_id = ?)))                    AS connected_with_me
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE u.id != ?
            ORDER BY u.created_at DESC
            LIMIT ?
            """, me, me, me, me, me, limit);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              r.get("id"));
            m.put("name",            (s(r,"first_name") + " " + s(r,"last_name")).trim());
            m.put("firstName",       s(r,"first_name"));
            m.put("lastName",        s(r,"last_name"));
            m.put("role",            s(r,"role"));
            m.put("bio",             s(r,"bio"));
            m.put("location",        s(r,"location"));
            m.put("institution",     s(r,"institution"));
            m.put("company",         s(r,"company"));
            m.put("jobTitle",        s(r,"job_title"));
            m.put("degree",          s(r,"degree"));
            m.put("year",            s(r,"year"));
            m.put("joinedAt",        s(r,"created_at"));
            m.put("connectionCount", toLong(r.get("connection_count")));
            m.put("connectedWithMe", toLong(r.get("connected_with_me")) > 0);
            m.put("pendingWithMe",   toLong(r.get("pending_with_me")) > 0);

            String skillsRaw = s(r, "skills");
            List<String> skills = skillsRaw.isEmpty() ? List.of()
                : Arrays.asList(skillsRaw.split(","));
            m.put("skills", skills);
            result.add(m);
        }
        return ResponseEntity.ok(result);
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
