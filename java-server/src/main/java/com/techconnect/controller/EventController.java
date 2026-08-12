package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Events & Meetups — full explicit paths (no class-level @RequestMapping).
 *
 * POST   /api/events                create event
 * GET    /api/events                list events (type filter, upcoming/past, pagination)
 * GET    /api/events/stats          { total, rsvped } for dashboard card
 * GET    /api/events/{id}           single event detail
 * POST   /api/events/{id}/rsvp      toggle RSVP
 * DELETE /api/events/{id}           delete own event
 */
@RestController
public class EventController {

    private static final Set<String> VALID_TYPES =
        Set.of("meetup","webinar","hackathon","workshop","other");

    private final JdbcTemplate jdbc;
    public EventController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Create ────────────────────────────────────────────────────────────────
    @PostMapping("/api/events")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> create(
            @RequestBody Map<String,Object> body, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String title = clean(body,"title");
        String desc  = clean(body,"description");
        String start = clean(body,"startTime");
        if (title.isEmpty()) return err(400,"Event title is required.");
        if (desc.isEmpty())  return err(400,"Description is required.");
        if (start.isEmpty()) return err(400,"Start date/time is required.");
        if (desc.length() > 2000) return err(400,"Description too long (max 2000 chars).");

        String type = clean(body,"eventType");
        if (!VALID_TYPES.contains(type)) type = "meetup";

        String location = clean(body,"location");
        String end      = clean(body,"endTime");

        int maxAtt = 0;
        try { maxAtt = Integer.parseInt(clean(body,"maxAttendees")); } catch (Exception ignored) {}

        String id  = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO events (id,creator_id,title,description,event_type,location,start_time,end_time,max_attendees)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, id, uid, title, desc, type, location, start, end, maxAtt);

        // Fetch creator name
        String creatorName = "";
        try {
            Map<String,Object> u = jdbc.queryForMap(
                "SELECT first_name,last_name FROM users WHERE id=?", uid);
            creatorName = (u.get("first_name")+" "+u.get("last_name")).trim();
        } catch (Exception ignored) {}

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("id",           id);
        resp.put("creatorId",    uid);
        resp.put("creatorName",  creatorName);
        resp.put("title",        title);
        resp.put("description",  desc);
        resp.put("eventType",    type);
        resp.put("location",     location);
        resp.put("startTime",    start);
        resp.put("endTime",      end);
        resp.put("maxAttendees", maxAtt);
        resp.put("attendeeCount",0);
        resp.put("rsvpedByMe",   false);
        resp.put("isMine",       true);
        resp.put("createdAt",    java.time.Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    // ── List ──────────────────────────────────────────────────────────────────
    @GetMapping("/api/events")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> list(
            @RequestParam(defaultValue="all")  String type,
            @RequestParam(defaultValue="upcoming") String when,   // upcoming | past | all
            @RequestParam(defaultValue="0")    int    offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String typeFilter = VALID_TYPES.contains(type) ? type : null;
        String nowIso = java.time.Instant.now().toString();

        String sql = "SELECT e.id, e.creator_id, e.title, e.description, e.event_type," +
            " e.location, e.start_time, e.end_time, e.max_attendees, e.created_at," +
            " u.first_name, u.last_name," +
            " (SELECT COUNT(*) FROM event_attendees a WHERE a.event_id=e.id) AS attendee_count," +
            " (SELECT COUNT(*) FROM event_attendees a WHERE a.event_id=e.id AND a.user_id=?) AS rsvped_by_me" +
            " FROM events e JOIN users u ON u.id=e.creator_id" +
            " WHERE 1=1" +
            (typeFilter != null ? " AND e.event_type=?" : "") +
            ("upcoming".equals(when) ? " AND e.start_time >= ?" :
             "past".equals(when)     ? " AND e.start_time < ?"  : "") +
            " ORDER BY e.start_time " + ("past".equals(when) ? "DESC" : "ASC") +
            " LIMIT 20 OFFSET ?";

        List<Object> params = new ArrayList<>();
        params.add(uid);
        if (typeFilter != null) params.add(typeFilter);
        if (!"all".equals(when)) params.add(nowIso);
        params.add(offset);

        List<Map<String,Object>> rows = jdbc.queryForList(sql, params.toArray());
        return ResponseEntity.ok(toEventList(rows, uid));
    }

    // ── Stats ─────────────────────────────────────────────────────────────────
    @GetMapping("/api/events/stats")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> stats(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String now = java.time.Instant.now().toString();
        Long total  = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE start_time>=?", Long.class, now);
        Long rsvped = jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_attendees ea JOIN events e ON e.id=ea.event_id WHERE ea.user_id=? AND e.start_time>=?",
            Long.class, uid, now);
        return ResponseEntity.ok(Map.of(
            "total",  total  == null ? 0 : total,
            "rsvped", rsvped == null ? 0 : rsvped));
    }

    // ── Single event ──────────────────────────────────────────────────────────
    @GetMapping("/api/events/{id}")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> getEvent(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        try {
            List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT e.id, e.creator_id, e.title, e.description, e.event_type," +
                " e.location, e.start_time, e.end_time, e.max_attendees, e.created_at," +
                " u.first_name, u.last_name," +
                " (SELECT COUNT(*) FROM event_attendees a WHERE a.event_id=e.id) AS attendee_count," +
                " (SELECT COUNT(*) FROM event_attendees a WHERE a.event_id=e.id AND a.user_id=?) AS rsvped_by_me" +
                " FROM events e JOIN users u ON u.id=e.creator_id WHERE e.id=?",
                uid, id);
            if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error","Event not found."));
            return ResponseEntity.ok(toEventList(rows, uid).get(0));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Toggle RSVP ───────────────────────────────────────────────────────────
    @PostMapping("/api/events/{id}/rsvp")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> rsvp(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE id=?", Integer.class, id);
        if (exists == null || exists == 0)
            return ResponseEntity.status(404).body(Map.of("error","Event not found."));

        // Check max attendees
        Map<String,Object> ev = null;
        try { ev = jdbc.queryForMap("SELECT max_attendees FROM events WHERE id=?", id); }
        catch (Exception ignored) {}

        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM event_attendees WHERE event_id=? AND user_id=?", Integer.class, id, uid);

        boolean nowRsvped;
        if (already != null && already > 0) {
            jdbc.update("DELETE FROM event_attendees WHERE event_id=? AND user_id=?", id, uid);
            nowRsvped = false;
        } else {
            // Enforce max attendees if set
            if (ev != null) {
                long max = toLong(ev.get("max_attendees"));
                if (max > 0) {
                    Long cur = jdbc.queryForObject("SELECT COUNT(*) FROM event_attendees WHERE event_id=?", Long.class, id);
                    if (cur != null && cur >= max)
                        return err(400,"Event is full ("+max+" attendees max).");
                }
            }
            jdbc.update("INSERT OR IGNORE INTO event_attendees (id,event_id,user_id) VALUES (?,?,?)",
                UUID.randomUUID().toString(), id, uid);
            nowRsvped = true;
        }

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM event_attendees WHERE event_id=?", Long.class, id);
        return ResponseEntity.ok(Map.of("rsvped", nowRsvped, "attendeeCount", count == null ? 0 : count));
    }

    // ── Delete own event ──────────────────────────────────────────────────────
    @DeleteMapping("/api/events/{id}")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> delete(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        int del = jdbc.update("DELETE FROM events WHERE id=? AND creator_id=?", id, uid);
        if (del == 0) return ResponseEntity.status(404).body(Map.of("error","Event not found or not yours."));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private List<Map<String,Object>> toEventList(List<Map<String,Object>> rows, String uid) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            Map<String,Object> e = new LinkedHashMap<>();
            e.put("id",           r.get("id"));
            e.put("creatorId",    s(r,"creator_id"));
            e.put("creatorName",  (s(r,"first_name")+" "+s(r,"last_name")).trim());
            e.put("title",        s(r,"title"));
            e.put("description",  s(r,"description"));
            e.put("eventType",    s(r,"event_type"));
            e.put("location",     s(r,"location"));
            e.put("startTime",    s(r,"start_time"));
            e.put("endTime",      s(r,"end_time"));
            e.put("maxAttendees", toLong(r.get("max_attendees")));
            e.put("attendeeCount",toLong(r.get("attendee_count")));
            e.put("rsvpedByMe",   toLong(r.get("rsvped_by_me")) > 0);
            e.put("isMine",       uid.equals(s(r,"creator_id")));
            e.put("createdAt",    s(r,"created_at"));
            out.add(e);
        }
        return out;
    }

    private static String clean(Map<String,Object> b, String k) {
        Object v = b.get(k); return v == null ? "" : v.toString().trim();
    }
    private static String s(Map<String,Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
    private static ResponseEntity<Map<String,Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
