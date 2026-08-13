package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final JdbcTemplate jdbc;

    public MeetingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // POST /api/meetings — student schedules a meeting with an IT professional
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String requesterId = (String) session.getAttribute("userId");
        if (requesterId == null) return err(401, "Not authenticated.");

        String userRole = jdbc.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, requesterId);
        if (!"student".equals(userRole)) return err(403, "Only students can schedule meetings.");

        String participantId = trim(body, "participantId");
        String title         = trim(body, "title");
        String scheduledTime = trim(body, "scheduledTime");
        int    duration      = body.get("durationMins") instanceof Number n ? n.intValue() : 30;
        String link          = trim(body, "meetingLink");
        String notes         = trim(body, "notes");

        if (participantId.isEmpty() || title.isEmpty() || scheduledTime.isEmpty())
            return err(400, "participantId, title, and scheduledTime are required.");

        List<Map<String, Object>> part = jdbc.queryForList(
            "SELECT id, role FROM users WHERE id = ?", participantId);
        if (part.isEmpty()) return err(404, "Participant not found.");

        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO meetings (id, requester_id, participant_id, title,
                scheduled_time, duration_mins, meeting_link, notes, status)
            VALUES (?,?,?,?,?,?,?,?,'pending')
            """, id, requesterId, participantId, title, scheduledTime, duration, link, notes);

        return ResponseEntity.status(201).body(buildMeeting(id, requesterId));
    }

    // GET /api/meetings — returns meetings where I am requester or participant
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT m.*,
                   ru.first_name || ' ' || ru.last_name AS requester_name,
                   pu.first_name || ' ' || pu.last_name AS participant_name
            FROM meetings m
            JOIN users ru ON ru.id = m.requester_id
            JOIN users pu ON pu.id = m.participant_id
            WHERE m.requester_id = ? OR m.participant_id = ?
            ORDER BY m.scheduled_time ASC
            """, userId, userId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              r.get("id"));
            m.put("requesterId",     r.get("requester_id"));
            m.put("requesterName",   r.get("requester_name"));
            m.put("participantId",   r.get("participant_id"));
            m.put("participantName", r.get("participant_name"));
            m.put("title",           r.get("title"));
            m.put("scheduledTime",   r.get("scheduled_time"));
            m.put("durationMins",    r.get("duration_mins"));
            m.put("meetingLink",     r.get("meeting_link"));
            m.put("notes",           r.get("notes"));
            m.put("status",          r.get("status"));
            m.put("createdAt",       r.get("created_at"));
            m.put("isMine",          userId.equals(r.get("requester_id")));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // PUT /api/meetings/{id}/status — accept or decline (participant only)
    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String userId = (String) session.getAttribute("userId");
        if (userId == null) return err(401, "Not authenticated.");

        String status = trim(body, "status");
        if (!List.of("accepted", "declined", "cancelled").contains(status))
            return err(400, "Invalid status. Use accepted, declined, or cancelled.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM meetings WHERE id = ?", id);
        if (rows.isEmpty()) return err(404, "Meeting not found.");

        Map<String, Object> meeting = rows.get(0);
        String participantId = (String) meeting.get("participant_id");
        String requesterId   = (String) meeting.get("requester_id");

        if ("cancelled".equals(status)) {
            if (!userId.equals(requesterId)) return err(403, "Only the requester can cancel.");
        } else {
            if (!userId.equals(participantId)) return err(403, "Only the participant can accept or decline.");
        }

        jdbc.update("UPDATE meetings SET status = ? WHERE id = ?", status, id);
        return ResponseEntity.ok(buildMeeting(id, userId));
    }

    // DELETE /api/meetings/{id} — requester cancels
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id,
            HttpSession session) {

        String userId = (String) session.getAttribute("userId");
        if (userId == null) return err(401, "Not authenticated.");

        int deleted = jdbc.update(
            "DELETE FROM meetings WHERE id = ? AND requester_id = ?", id, userId);
        if (deleted == 0) return err(404, "Meeting not found or not yours.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // GET /api/meetings/connections — IT pros the student is connected to
    @GetMapping("/connections")
    public ResponseEntity<List<Map<String, Object>>> connections(HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.first_name || ' ' || u.last_name AS name, u.role,
                   COALESCE(p.job_title,'') AS jobTitle, COALESCE(p.company,'') AS company
            FROM connections c
            JOIN users u ON u.id = CASE WHEN c.requester_id = ? THEN c.recipient_id ELSE c.requester_id END
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE (c.requester_id = ? OR c.recipient_id = ?)
              AND c.status = 'accepted'
              AND u.role = 'pro'
            ORDER BY name
            """, userId, userId, userId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       r.get("id"));
            m.put("name",     r.get("name"));
            m.put("role",     r.get("role"));
            m.put("jobTitle", r.get("jobTitle"));
            m.put("company",  r.get("company"));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildMeeting(String id, String viewerId) {
        Map<String, Object> r = jdbc.queryForList("""
            SELECT m.*,
                   ru.first_name || ' ' || ru.last_name AS requester_name,
                   pu.first_name || ' ' || pu.last_name AS participant_name
            FROM meetings m
            JOIN users ru ON ru.id = m.requester_id
            JOIN users pu ON pu.id = m.participant_id
            WHERE m.id = ?
            """, id).get(0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              r.get("id"));
        m.put("requesterId",     r.get("requester_id"));
        m.put("requesterName",   r.get("requester_name"));
        m.put("participantId",   r.get("participant_id"));
        m.put("participantName", r.get("participant_name"));
        m.put("title",           r.get("title"));
        m.put("scheduledTime",   r.get("scheduled_time"));
        m.put("durationMins",    r.get("duration_mins"));
        m.put("meetingLink",     r.get("meeting_link"));
        m.put("notes",           r.get("notes"));
        m.put("status",          r.get("status"));
        m.put("createdAt",       r.get("created_at"));
        m.put("isMine",          viewerId.equals(r.get("requester_id")));
        return m;
    }

    private static String trim(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
