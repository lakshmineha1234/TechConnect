package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GET /api/users/suggestions   — "People You May Know"
 *
 * Scores every non-connected user by:
 *   +3 per mutual connection
 *   +2 per shared skill
 *   +1 if same role
 *
 * Returns top N results (default 8, max 20) with:
 *   id, name, role, bio, location, company, institution, skills,
 *   mutualCount, sharedSkills (list), score
 */
@RestController
public class SuggestionController {

    private final JdbcTemplate jdbc;

    public SuggestionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/users/suggestions")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> suggestions(
            @RequestParam(defaultValue = "8") int limit,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();
        limit = Math.min(limit, 20);

        // ── 1. Ids I'm already connected to or have pending with ──────────────
        List<String> excluded = new ArrayList<>();
        excluded.add(me);
        jdbc.queryForList("""
                SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END AS other
                FROM connections
                WHERE requester_id = ? OR recipient_id = ?
                """, me, me, me)
            .forEach(r -> excluded.add((String) r.get("other")));

        // ── 2. My accepted connections (for mutual-connection scoring) ─────────
        List<String> myConnections = jdbc.queryForList("""
                SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END AS other
                FROM connections WHERE status = 'accepted'
                  AND (requester_id = ? OR recipient_id = ?)
                """, me, me, me)
            .stream().map(r -> (String) r.get("other")).collect(Collectors.toList());

        // ── 3. My skills ───────────────────────────────────────────────────────
        List<String> mySkills = jdbc.queryForList(
                "SELECT skill_name FROM skills WHERE user_id = ?", me)
            .stream().map(r -> ((String) r.get("skill_name")).toLowerCase()).collect(Collectors.toList());

        // ── 4. My role ────────────────────────────────────────────────────────
        String myRole = "";
        try { myRole = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, me); }
        catch (Exception ignored) {}

        // ── 5. Fetch all candidate users (not excluded) ───────────────────────
        String ph = String.join(",", Collections.nCopies(excluded.size(), "?"));
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT u.id, u.first_name, u.last_name, u.role, " +
                "p.bio, p.location, p.company, p.institution, p.open_to_work, p.is_hiring " +
                "FROM users u LEFT JOIN profiles p ON p.user_id = u.id " +
                "WHERE u.id NOT IN (" + ph + ")",
                excluded.toArray());

        if (candidates.isEmpty()) return ResponseEntity.ok(List.of());

        // ── 6. Skills for all candidates ──────────────────────────────────────
        List<String> candIds = candidates.stream().map(r -> (String) r.get("id")).collect(Collectors.toList());
        String ph2 = String.join(",", Collections.nCopies(candIds.size(), "?"));
        Map<String, List<String>> candSkillsMap = new HashMap<>();
        jdbc.queryForList(
                "SELECT user_id, skill_name FROM skills WHERE user_id IN (" + ph2 + ")",
                candIds.toArray())
            .forEach(r -> {
                String uid = (String) r.get("user_id");
                candSkillsMap.computeIfAbsent(uid, k -> new ArrayList<>()).add((String) r.get("skill_name"));
            });

        // ── 7. Connections of my connections (for mutual count) ────────────────
        Map<String, Integer> mutualCount = new HashMap<>();
        if (!myConnections.isEmpty()) {
            String ph3  = String.join(",", Collections.nCopies(myConnections.size(), "?"));
            String sql3 = "SELECT CASE WHEN requester_id IN (" + ph3 + ") THEN recipient_id ELSE requester_id END AS other"
                        + " FROM connections WHERE status = 'accepted'"
                        + " AND (requester_id IN (" + ph3 + ") OR recipient_id IN (" + ph3 + "))";
            jdbc.queryForList(sql3, concat(myConnections, myConnections, myConnections).toArray())
                .forEach(r -> {
                    String other = (String) r.get("other");
                    if (!excluded.contains(other)) {
                        mutualCount.merge(other, 1, Integer::sum);
                    }
                });
        }

        // ── 8. Score and sort ─────────────────────────────────────────────────
        final String finalMyRole = myRole;
        List<Map<String, Object>> scored = candidates.stream().map(c -> {
            String cid    = (String) c.get("id");
            String crole  = s(c, "role");
            List<String> cSkills = candSkillsMap.getOrDefault(cid, List.of());

            int mutual = mutualCount.getOrDefault(cid, 0);
            List<String> shared = cSkills.stream()
                .filter(sk -> mySkills.contains(sk.toLowerCase()))
                .collect(Collectors.toList());

            int score = mutual * 3 + shared.size() * 2 + (crole.equals(finalMyRole) ? 1 : 0);

            String fn   = s(c, "first_name");
            String ln   = s(c, "last_name");
            String name = (fn + " " + ln).trim();
            if (name.isEmpty()) name = cid;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id",          cid);
            r.put("name",        name);
            r.put("firstName",   fn);
            r.put("lastName",    ln);
            r.put("role",        crole);
            r.put("bio",         s(c, "bio"));
            r.put("location",    s(c, "location"));
            r.put("company",     s(c, "company"));
            r.put("institution", s(c, "institution"));
            r.put("skills",      cSkills);
            r.put("mutualCount", mutual);
            r.put("sharedSkills",shared);
            r.put("score",       score);
            r.put("openToWork",  toBool(c.get("open_to_work")));
            r.put("isHiring",    toBool(c.get("is_hiring")));
            return r;
        })
        .sorted(Comparator.comparingInt((Map<String,Object> m) -> -(int) m.get("score"))
                          .thenComparing(m -> (String) m.get("name")))
        .limit(limit)
        .collect(Collectors.toList());

        return ResponseEntity.ok(scored);
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number  n) return n.intValue() != 0;
        return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> l : lists) out.addAll(l);
        return out;
    }
}
