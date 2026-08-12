package com.techconnect.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GET /api/search — public endpoint, no auth required.
 *
 * Query params:
 *   q     – free-text (name, bio, institution, company, location, skills)
 *   role  – all | student | pro   (default: all)
 *   limit – max results, capped at 50  (default: 20)
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final JdbcTemplate jdbc;

    public SearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(defaultValue = "")    String q,
            @RequestParam(defaultValue = "all") String role,
            @RequestParam(defaultValue = "20")  int    limit) {

        limit = Math.min(limit, 500);
        String like       = "%" + q.toLowerCase() + "%";
        boolean filterRole = !role.equals("all");
        boolean hasQuery   = !q.isEmpty();

        // ── 1. Fetch matching user + profile rows ─────────────────────────────
        String sql = """
                SELECT u.id, u.first_name, u.last_name, u.role,
                       p.bio, p.institution, p.company, p.location
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                WHERE (? = 0 OR u.role = ?)
                  AND (? = 0 OR (
                       LOWER(u.first_name) LIKE ? OR LOWER(u.last_name) LIKE ?
                    OR LOWER(p.bio)         LIKE ? OR LOWER(p.institution) LIKE ?
                    OR LOWER(p.company)     LIKE ? OR LOWER(p.location)    LIKE ?
                    OR EXISTS (
                         SELECT 1 FROM skills sk
                         WHERE sk.user_id = u.id AND LOWER(sk.skill_name) LIKE ?
                       )
                  ))
                LIMIT ?
                """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql,
            filterRole ? 1 : 0, role,
            hasQuery   ? 1 : 0,
            like, like, like, like, like, like, like,
            limit);

        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("results", List.of(), "total", 0));
        }

        // ── 2. Fetch skills for matching users ────────────────────────────────
        List<String> ids   = rows.stream().map(r -> (String) r.get("id")).collect(Collectors.toList());
        String       ph    = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> skillRows = jdbc.queryForList(
                "SELECT user_id, skill_name FROM skills WHERE user_id IN (" + ph + ") ORDER BY id",
                ids.toArray());

        Map<String, List<String>> skillsMap = new HashMap<>();
        for (Map<String, Object> sr : skillRows) {
            String uid = (String) sr.get("user_id");
            skillsMap.computeIfAbsent(uid, k -> new ArrayList<>())
                     .add((String) sr.get("skill_name"));
        }

        // ── 3. Build response ─────────────────────────────────────────────────
        List<Map<String, Object>> results = rows.stream().map(row -> {
            String id = (String) row.get("id");
            String fn = str(row, "first_name");
            String ln = str(row, "last_name");
            String name = (fn + " " + ln).trim();
            if (name.isEmpty()) name = id;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id",          id);
            r.put("name",        name);
            r.put("firstName",   fn);
            r.put("lastName",    ln);
            r.put("role",        row.get("role"));
            r.put("bio",         str(row, "bio"));
            r.put("institution", str(row, "institution"));
            r.put("company",     str(row, "company"));
            r.put("location",    str(row, "location"));
            r.put("skills",      skillsMap.getOrDefault(id, List.of()));
            return r;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("results", results, "total", results.size()));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}
