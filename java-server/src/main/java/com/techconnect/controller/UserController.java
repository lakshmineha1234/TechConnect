package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GET /api/users/{id}/profile
 * Returns the public profile of any user, plus the calling user's
 * connection status with that user.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final JdbcTemplate jdbc;

    public UserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<Map<String, Object>> getUserProfile(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        List<Map<String, Object>> userRows =
                jdbc.queryForList("SELECT * FROM users    WHERE id      = ?", id);
        List<Map<String, Object>> profRows =
                jdbc.queryForList("SELECT * FROM profiles WHERE user_id = ?", id);

        if (userRows.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));

        Map<String, Object> u = userRows.get(0);
        Map<String, Object> p = profRows.isEmpty() ? Map.of() : profRows.get(0);

        List<String> skills = jdbc
                .queryForList("SELECT skill_name FROM skills WHERE user_id = ? ORDER BY id", id)
                .stream().map(r -> (String) r.get("skill_name")).collect(Collectors.toList());

        // Connection status between me and this user
        List<Map<String, Object>> conns = jdbc.queryForList(
                """
                SELECT id, status, requester_id FROM connections
                WHERE (requester_id = ? AND recipient_id = ?)
                   OR (requester_id = ? AND recipient_id = ?)
                """, me, id, id, me);

        String connStatus = "none";
        String connId     = "";
        if (!conns.isEmpty()) {
            Map<String, Object> c = conns.get(0);
            String status  = (String) c.get("status");
            String reqId   = (String) c.get("requester_id");
            connId         = (String) c.get("id");
            if ("accepted".equals(status))  connStatus = "accepted";
            else if ("pending".equals(status))
                connStatus = me.equals(reqId) ? "pending" : "received";
            else connStatus = "none"; // declined → treat as none
        }

        String fn = s(u, "first_name");
        String ln = s(u, "last_name");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",          id);
        result.put("name",        (fn + " " + ln).trim());
        result.put("firstName",   fn);
        result.put("lastName",    ln);
        result.put("role",        s(u, "role"));
        result.put("location",    s(p, "location"));
        result.put("bio",         s(p, "bio"));
        result.put("institution", s(p, "institution"));
        result.put("degree",      s(p, "degree"));
        result.put("year",        s(p, "year"));
        result.put("company",     s(p, "company"));
        result.put("jobTitle",    s(p, "job_title"));
        result.put("experience",  s(p, "experience"));
        result.put("linkedin",    s(p, "linkedin"));
        result.put("github",      s(p, "github"));
        result.put("skills",      skills);
        result.put("connStatus",  connStatus);
        result.put("connId",      connId);
        result.put("isSelf",      me.equals(id));
        return ResponseEntity.ok(result);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
}
