package com.techconnect.controller;

import com.techconnect.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Skill Endorsements
 *
 * POST /api/users/{userId}/endorse          toggle endorsement for a skill
 *       body: { skillName: "Java" }
 *
 * GET  /api/users/{userId}/endorsements     all endorsement counts + which ones I gave
 *       returns: [ { skillName, count, endorsedByMe }, … ]
 */
@RestController
public class EndorsementController {

    private final JdbcTemplate       jdbc;
    private final NotificationService notifSvc;

    public EndorsementController(JdbcTemplate jdbc, NotificationService notifSvc) {
        this.jdbc     = jdbc;
        this.notifSvc = notifSvc;
    }

    // ── Toggle endorsement ────────────────────────────────────────────────────
    @PostMapping("/api/users/{userId}/endorse")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> toggle(
            @PathVariable String userId,
            @RequestBody Map<String,Object> body,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");
        if (me.equals(userId)) return err(400, "You cannot endorse your own skills.");

        String skillName = body.get("skillName") == null ? "" : body.get("skillName").toString().trim();
        if (skillName.isEmpty()) return err(400, "Skill name is required.");
        if (skillName.length() > 80) return err(400, "Skill name too long.");

        // Must be a connection
        Integer connected = jdbc.queryForObject("""
            SELECT COUNT(*) FROM connections
            WHERE status = 'accepted'
              AND ((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))
            """, Integer.class, me, userId, userId, me);
        if (connected == null || connected == 0)
            return err(403, "You can only endorse skills of your connections.");

        // Skill must exist on that user's profile
        Integer hasSkill = jdbc.queryForObject(
            "SELECT COUNT(*) FROM skills WHERE user_id=? AND LOWER(skill_name)=LOWER(?)",
            Integer.class, userId, skillName);
        if (hasSkill == null || hasSkill == 0)
            return err(404, "That skill doesn't exist on this user's profile.");

        // Toggle
        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM skill_endorsements WHERE endorsed_id=? AND endorser_id=? AND LOWER(skill_name)=LOWER(?)",
            Integer.class, userId, me, skillName);

        boolean nowEndorsed;
        if (already != null && already > 0) {
            jdbc.update(
                "DELETE FROM skill_endorsements WHERE endorsed_id=? AND endorser_id=? AND LOWER(skill_name)=LOWER(?)",
                userId, me, skillName);
            nowEndorsed = false;
        } else {
            jdbc.update(
                "INSERT OR IGNORE INTO skill_endorsements (id,endorsed_id,endorser_id,skill_name) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), userId, me, skillName);
            nowEndorsed = true;
            notifSvc.create(userId, "skill_endorsement", me, skillName);
        }

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM skill_endorsements WHERE endorsed_id=? AND LOWER(skill_name)=LOWER(?)",
            Long.class, userId, skillName);

        return ResponseEntity.ok(Map.of(
            "skillName",   skillName,
            "endorsed",    nowEndorsed,
            "count",       count == null ? 0 : count
        ));
    }

    // ── Get all endorsements for a user ───────────────────────────────────────
    @GetMapping("/api/users/{userId}/endorsements")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> list(
            @PathVariable String userId, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return ResponseEntity.status(401).build();

        // All skills of the target user, with endorsement count and whether I endorsed
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT s.skill_name,
                   COUNT(e.id)                                            AS endorse_count,
                   SUM(CASE WHEN e.endorser_id = ? THEN 1 ELSE 0 END)    AS endorsed_by_me
            FROM skills s
            LEFT JOIN skill_endorsements e
                   ON LOWER(e.skill_name) = LOWER(s.skill_name) AND e.endorsed_id = s.user_id
            WHERE s.user_id = ?
            GROUP BY s.skill_name
            ORDER BY endorse_count DESC, s.skill_name ASC
            """, me, userId);

        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("skillName",    r.get("skill_name"));
            item.put("count",        toLong(r.get("endorse_count")));
            item.put("endorsedByMe", toLong(r.get("endorsed_by_me")) > 0);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l)    return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
    private static ResponseEntity<Map<String,Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
