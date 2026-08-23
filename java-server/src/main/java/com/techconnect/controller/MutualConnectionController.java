package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GET /api/users/{userId}/mutual
 * Returns the mutual (accepted) connections shared between the caller and the target user.
 * Responds with a list of { id, name, role } objects, capped at 20.
 */
@RestController
public class MutualConnectionController {

    private final JdbcTemplate jdbc;

    public MutualConnectionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/users/{userId}/mutual")
    public ResponseEntity<List<Map<String, Object>>> mutual(
            @PathVariable String userId, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        // My accepted connections
        List<String> myConns = jdbc.queryForList("""
            SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END AS other
            FROM connections WHERE status = 'accepted'
              AND (requester_id = ? OR recipient_id = ?)
            """, me, me, me)
            .stream().map(r -> (String) r.get("other")).collect(Collectors.toList());

        if (myConns.isEmpty()) return ResponseEntity.ok(List.of());

        // Target's accepted connections that are also in mine
        String ph = String.join(",", Collections.nCopies(myConns.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(userId); params.add(userId);
        params.addAll(myConns);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT CASE WHEN c.requester_id = ? THEN c.recipient_id ELSE c.requester_id END AS other " +
            "FROM connections c " +
            "WHERE c.status = 'accepted' " +
            "  AND (c.requester_id = ? OR c.recipient_id = ?) " +
            "  AND CASE WHEN c.requester_id = ? THEN c.recipient_id ELSE c.requester_id END IN (" + ph + ") " +
            "LIMIT 20",
            buildParams(userId, userId, userId, myConns));

        if (rows.isEmpty()) return ResponseEntity.ok(List.of());

        List<String> mutualIds = rows.stream()
            .map(r -> (String) r.get("other"))
            .distinct().collect(Collectors.toList());

        String ph2 = String.join(",", Collections.nCopies(mutualIds.size(), "?"));
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT u.id, u.first_name, u.last_name, u.role FROM users u WHERE u.id IN (" + ph2 + ")",
            mutualIds.toArray());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> u : users) {
            String fn = s(u, "first_name");
            String ln = s(u, "last_name");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",   u.get("id"));
            item.put("name", (fn + " " + ln).trim());
            item.put("role", s(u, "role"));
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    private static Object[] buildParams(String a, String b, String c, List<String> rest) {
        List<Object> p = new ArrayList<>();
        p.add(a); p.add(b); p.add(c); p.add(c);
        p.addAll(rest);
        return p.toArray();
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
}
