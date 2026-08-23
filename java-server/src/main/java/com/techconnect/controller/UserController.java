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
        // Connection degree: 1 = direct, 2 = mutual friend, 3 = further
        int connDegree = 3;
        if (me.equals(id)) {
            connDegree = 0; // self
        } else if ("accepted".equals(connStatus)) {
            connDegree = 1;
        } else {
            Integer mutual = jdbc.queryForObject("""
                SELECT COUNT(*) FROM connections c1
                JOIN connections c2 ON (
                  CASE WHEN c1.requester_id=? THEN c1.recipient_id ELSE c1.requester_id END
                  = CASE WHEN c2.requester_id=? THEN c2.recipient_id ELSE c2.requester_id END
                )
                WHERE c1.status='accepted' AND c2.status='accepted'
                  AND (c1.requester_id=? OR c1.recipient_id=?)
                  AND (c2.requester_id=? OR c2.recipient_id=?)
                LIMIT 1
                """, Integer.class, me, id, me, me, id, id);
            if (mutual != null && mutual > 0) connDegree = 2;
        }

        result.put("connStatus",  connStatus);
        result.put("connId",      connId);
        result.put("isSelf",      me.equals(id));
        result.put("connDegree",  connDegree);
        result.put("openToWork",  toBool(p.get("open_to_work")));
        result.put("isHiring",    toBool(p.get("is_hiring")));
        return ResponseEntity.ok(result);
    }

    // ── List skill endorsements for a user ───────────────────────────────────
    @GetMapping("/{id}/endorsements")
    public ResponseEntity<List<Map<String, Object>>> getEndorsements(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        List<String> skills = jdbc.queryForList(
                "SELECT skill_name FROM skills WHERE user_id = ? ORDER BY id",
                String.class, id);

        List<Map<String, Object>> result = new ArrayList<>();
        for (String skill : skills) {
            long count = toLong(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM skill_endorsements WHERE endorsed_id = ? AND skill_name = ?",
                    Long.class, id, skill));
            boolean endorsedByMe = me.equals(id) ? false :
                toLong(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM skill_endorsements WHERE endorsed_id = ? AND endorser_id = ? AND skill_name = ?",
                    Long.class, id, me, skill)) > 0;

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("skillName",    skill);
            e.put("count",        count);
            e.put("endorsedByMe", endorsedByMe);
            result.add(e);
        }
        return ResponseEntity.ok(result);
    }

    // ── Toggle endorsement ────────────────────────────────────────────────────
    @PostMapping("/{id}/endorse")
    public ResponseEntity<Map<String, Object>> toggleEndorse(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();
        if (me.equals(id)) return ResponseEntity.status(400).body(Map.of("error", "Cannot endorse yourself."));

        String skillName = body == null ? "" : body.getOrDefault("skillName", "").trim();
        if (skillName.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "skillName required."));

        // Skill must belong to that user
        Integer owns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skills WHERE user_id = ? AND skill_name = ?",
                Integer.class, id, skillName);
        if (owns == null || owns == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Skill not found."));

        Integer already = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_endorsements WHERE endorsed_id = ? AND endorser_id = ? AND skill_name = ?",
                Integer.class, id, me, skillName);

        boolean nowEndorsed;
        if (already != null && already > 0) {
            jdbc.update("DELETE FROM skill_endorsements WHERE endorsed_id = ? AND endorser_id = ? AND skill_name = ?",
                    id, me, skillName);
            nowEndorsed = false;
        } else {
            jdbc.update("INSERT OR IGNORE INTO skill_endorsements (id, endorsed_id, endorser_id, skill_name) VALUES (?,?,?,?)",
                    UUID.randomUUID().toString(), id, me, skillName);
            nowEndorsed = true;

            // Notify the skill owner
            try {
                String nid = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO notifications (id, user_id, type, actor_id, ref_id) VALUES (?,?,?,?,?)",
                        nid, id, "skill_endorsement", me, skillName);
            } catch (Exception ignored) {}
        }

        long count = toLong(jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_endorsements WHERE endorsed_id = ? AND skill_name = ?",
                Long.class, id, skillName));

        return ResponseEntity.ok(Map.of("endorsed", nowEndorsed, "count", count, "skillName", skillName));
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number  n) return n.intValue() != 0;
        return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
}
