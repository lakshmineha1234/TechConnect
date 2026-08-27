package com.techconnect.controller;

import com.techconnect.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Connection request system.
 *
 *  POST  /api/connections/request          { targetUserId }  → send request
 *  POST  /api/connections/{id}/accept                        → accept (recipient only)
 *  POST  /api/connections/{id}/decline                       → decline (recipient only)
 *  DELETE /api/connections/{id}                              → cancel sent / remove accepted
 *  GET   /api/connections/me               → { accepted, pendingIn, pendingSent, count }
 */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final JdbcTemplate jdbc;
    private final NotificationService notifSvc;

    public ConnectionController(JdbcTemplate jdbc, NotificationService notifSvc) {
        this.jdbc = jdbc;
        this.notifSvc = notifSvc;
    }

    // ── Send a connection request ────────────────────────────────────────────
    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> sendRequest(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err("Not logged in");

        String target = str(body, "targetUserId");
        if (target == null || target.isBlank()) return err("targetUserId required");
        if (target.equals(me)) return err("Cannot connect with yourself");

        // Check target user exists
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, target);
        if (exists == null || exists == 0) return err("User not found");

        // Check for existing connection in either direction
        List<Map<String, Object>> existing = jdbc.queryForList(
                """
                SELECT id, status, requester_id FROM connections
                WHERE (requester_id = ? AND recipient_id = ?)
                   OR (requester_id = ? AND recipient_id = ?)
                """, me, target, target, me);

        if (!existing.isEmpty()) {
            String status = (String) existing.get(0).get("status");
            String requesterId = (String) existing.get(0).get("requester_id");
            if ("accepted".equals(status))  return err("Already connected");
            if ("pending".equals(status) && me.equals(requesterId))
                return err("Request already sent");
            if ("pending".equals(status))
                return err("This user already sent you a request");
            // declined → allow re-request: delete and re-insert below
            jdbc.update("DELETE FROM connections WHERE id = ?", existing.get(0).get("id"));
        }

        String note = str(body, "note");
        if (note == null) note = "";
        if (note.length() > 300) note = note.substring(0, 300);

        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO connections (id, requester_id, recipient_id, status, note) VALUES (?,?,?,'pending',?)",
                id, me, target, note);

        // Notify the recipient that they received a connection request
        notifSvc.create(target, "connection_request", me, id);

        return ok(Map.of("id", id, "status", "pending"));
    }

    // ── Accept a request ─────────────────────────────────────────────────────
    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @PathVariable String id,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err("Not logged in");

        int rows = jdbc.update(
                """
                UPDATE connections
                SET status = 'accepted', updated_at = NOW()
                WHERE id = ? AND recipient_id = ? AND status = 'pending'
                """, id, me);

        if (rows == 0) return err("Request not found or not authorised");

        // Notify the original requester that their request was accepted
        try {
            String requesterId = jdbc.queryForObject(
                    "SELECT requester_id FROM connections WHERE id = ?", String.class, id);
            notifSvc.create(requesterId, "connection_accepted", me, id);
        } catch (Exception ignored) {}

        return ok(Map.of("id", id, "status", "accepted"));
    }

    // ── Decline a request ────────────────────────────────────────────────────
    @PostMapping("/{id}/decline")
    public ResponseEntity<Map<String, Object>> decline(
            @PathVariable String id,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err("Not logged in");

        int rows = jdbc.update(
                """
                UPDATE connections
                SET status = 'declined', updated_at = NOW()
                WHERE id = ? AND recipient_id = ? AND status = 'pending'
                """, id, me);

        if (rows == 0) return err("Request not found or not authorised");
        return ok(Map.of("id", id, "status", "declined"));
    }

    // ── Cancel sent request or remove accepted connection ────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(
            @PathVariable String id,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err("Not logged in");

        int rows = jdbc.update(
                """
                DELETE FROM connections
                WHERE id = ? AND (requester_id = ? OR recipient_id = ?)
                """, id, me, me);

        if (rows == 0) return err("Connection not found");
        return ok(Map.of("removed", true));
    }

    // ── Get all connection data for the current user ─────────────────────────
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> myConnections(HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err("Not logged in");

        // All connections involving me
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT c.id, c.requester_id, c.recipient_id, c.status, c.created_at, c.note,
                       u.first_name, u.last_name, u.role,
                       p.bio, p.company, p.institution, p.location,
                       p.open_to_work, p.is_hiring
                FROM connections c
                JOIN users u ON u.id = CASE
                    WHEN c.requester_id = ? THEN c.recipient_id
                    ELSE c.requester_id
                END
                LEFT JOIN profiles p ON p.user_id = u.id
                WHERE (c.requester_id = ? OR c.recipient_id = ?)
                  AND c.status != 'declined'
                ORDER BY c.updated_at DESC
                """, me, me, me);

        // Fetch skills for all those users
        List<String> otherIds = rows.stream().map(r -> {
            String req = (String) r.get("requester_id");
            return me.equals(req) ? (String) r.get("recipient_id") : (String) r.get("requester_id");
        }).distinct().toList();

        Map<String, List<String>> skillsMap = new HashMap<>();
        if (!otherIds.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(otherIds.size(), "?"));
            jdbc.queryForList(
                    "SELECT user_id, skill_name FROM skills WHERE user_id IN (" + ph + ")",
                    otherIds.toArray()
            ).forEach(sr -> {
                String uid = (String) sr.get("user_id");
                skillsMap.computeIfAbsent(uid, k -> new ArrayList<>())
                         .add((String) sr.get("skill_name"));
            });
        }

        List<Map<String, Object>> accepted    = new ArrayList<>();
        List<Map<String, Object>> pendingIn   = new ArrayList<>();
        List<Map<String, Object>> pendingSent = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            String connId      = (String) r.get("id");
            String requesterId = (String) r.get("requester_id");
            String recipientId = (String) r.get("recipient_id");
            String status      = (String) r.get("status");
            String otherId     = me.equals(requesterId) ? recipientId : requesterId;

            String fn   = s(r, "first_name");
            String ln   = s(r, "last_name");
            String name = (fn + " " + ln).trim();
            if (name.isEmpty()) name = otherId;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("connId",      connId);
            entry.put("userId",      otherId);
            entry.put("name",        name);
            entry.put("firstName",   fn);
            entry.put("lastName",    ln);
            entry.put("role",        r.get("role"));
            entry.put("bio",         s(r, "bio"));
            entry.put("company",     s(r, "company"));
            entry.put("institution", s(r, "institution"));
            entry.put("location",    s(r, "location"));
            entry.put("skills",      skillsMap.getOrDefault(otherId, List.of()));
            entry.put("createdAt",   r.get("created_at"));
            entry.put("openToWork",  toBool(r.get("open_to_work")));
            entry.put("isHiring",    toBool(r.get("is_hiring")));
            entry.put("requesterId", requesterId);

            if ("accepted".equals(status)) {
                accepted.add(entry);
            } else if ("pending".equals(status)) {
                if (me.equals(requesterId)) {
                    pendingSent.add(entry);
                } else {
                    entry.put("note", s(r, "note")); // only show note to recipient
                    pendingIn.add(entry);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted",    accepted);
        result.put("pendingIn",   pendingIn);
        result.put("pendingSent", pendingSent);
        result.put("count",       accepted.size());
        return ResponseEntity.ok(result);
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString().trim();
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
    private static ResponseEntity<Map<String, Object>> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }
}
