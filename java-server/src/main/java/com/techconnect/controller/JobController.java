package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Job Board — uses full explicit paths on every method (no class-level @RequestMapping)
 * to avoid conflicts with StaticController's @GetMapping("/**").
 *
 * POST   /api/jobs                  create a job posting (pro only)
 * GET    /api/jobs                  list jobs (search, type filter, pagination)
 * GET    /api/jobs/stats            { total } for dashboard Resources card
 * GET    /api/jobs/{id}             get single job with full details
 * POST   /api/jobs/{id}/save        toggle save/unsave
 * DELETE /api/jobs/{id}             delete own job
 */
@RestController
public class JobController {

    private final JdbcTemplate jdbc;

    public JobController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Create a job posting ──────────────────────────────────────────────────
    @PostMapping("/api/jobs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createJob(
            @RequestBody Map<String, Object> body, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String role = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, uid);
        if (!"pro".equals(role)) return err(403, "Only IT professionals can post jobs.");

        String title   = clean(body, "title");
        String company = clean(body, "company");
        String desc    = clean(body, "description");
        if (title.isEmpty())   return err(400, "Job title is required.");
        if (company.isEmpty()) return err(400, "Company name is required.");
        if (desc.isEmpty())    return err(400, "Job description is required.");
        if (desc.length() > 3000) return err(400, "Description too long (max 3000 chars).");

        String type = clean(body, "type");
        if (!List.of("full-time","part-time","internship","freelance","remote").contains(type))
            type = "full-time";

        String location = clean(body, "location");
        String skills   = clean(body, "skills");
        String salary   = clean(body, "salary");
        String now      = java.time.Instant.now().toString();

        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO jobs (id, user_id, title, company, location, type, description, skills, salary)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                id, uid, title, company, location, type, desc, skills, salary);

        // Look up poster name for the response
        String posterName = "";
        try {
            Map<String, Object> u = jdbc.queryForMap(
                    "SELECT first_name, last_name FROM users WHERE id = ?", uid);
            posterName = (u.get("first_name") + " " + u.get("last_name")).trim();
        } catch (Exception ignored) {}

        // Build response directly — no second SELECT so nothing can be null
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",          id);
        resp.put("userId",      uid);
        resp.put("title",       title);
        resp.put("company",     company);
        resp.put("location",    location);
        resp.put("type",        type);
        resp.put("description", desc);
        resp.put("skills",      skills);
        resp.put("salary",      salary);
        resp.put("createdAt",   now);
        resp.put("posterName",  posterName);
        resp.put("savedByMe",      false);
        resp.put("appliedByMe",    false);
        resp.put("applicantCount", 0);
        resp.put("isMine",         true);
        return ResponseEntity.ok(resp);
    }

    // ── List / search jobs ────────────────────────────────────────────────────
    @GetMapping("/api/jobs")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> listJobs(
            @RequestParam(defaultValue = "")    String q,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "0")   int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String search     = "%" + q.trim().toLowerCase() + "%";
        String typeFilter = type.equals("all") ? "%" : type;

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT j.id, j.user_id, j.title, j.company, j.location, j.type,
                       j.description, j.skills, j.salary, j.created_at,
                       u.first_name, u.last_name,
                       (SELECT COUNT(*) FROM job_saves       s WHERE s.job_id = j.id AND s.user_id = ?)   AS saved_by_me,
                       (SELECT COUNT(*) FROM job_applications a WHERE a.job_id = j.id AND a.applicant_id = ?) AS applied_by_me,
                       (SELECT COUNT(*) FROM job_applications a WHERE a.job_id = j.id)                    AS applicant_count
                FROM jobs j
                JOIN users u ON u.id = j.user_id
                WHERE j.type LIKE ?
                  AND (LOWER(j.title)    LIKE ? OR LOWER(j.company) LIKE ?
                    OR LOWER(j.skills)   LIKE ? OR LOWER(j.location) LIKE ?)
                ORDER BY j.created_at DESC
                LIMIT 20 OFFSET ?
                """, uid, uid, typeFilter, search, search, search, search, offset);

        return ResponseEntity.ok(toJobList(rows, uid));
    }

    // ── Stats for dashboard Resources card (must come before /{id}) ───────────
    @GetMapping("/api/jobs/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stats(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Long.class);
        Long saved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_saves WHERE user_id = ?", Long.class, uid);

        return ResponseEntity.ok(Map.of(
                "total", total == null ? 0 : total,
                "saved", saved == null ? 0 : saved));
    }

    // ── Single job ────────────────────────────────────────────────────────────
    @GetMapping("/api/jobs/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getJob(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Map<String, Object> job = getJobById(id, uid);
        if (job == null) return ResponseEntity.status(404).body(Map.of("error", "Job not found."));
        return ResponseEntity.ok(job);
    }

    // ── Toggle save ───────────────────────────────────────────────────────────
    @PostMapping("/api/jobs/{id}/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleSave(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Job not found."));

        Integer already = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_saves WHERE job_id = ? AND user_id = ?",
                Integer.class, id, uid);

        boolean nowSaved;
        if (already != null && already > 0) {
            jdbc.update("DELETE FROM job_saves WHERE job_id = ? AND user_id = ?", id, uid);
            nowSaved = false;
        } else {
            jdbc.update("INSERT OR IGNORE INTO job_saves (id, job_id, user_id) VALUES (?,?,?)",
                    UUID.randomUUID().toString(), id, uid);
            nowSaved = true;
        }
        return ResponseEntity.ok(Map.of("saved", nowSaved));
    }

    // ── Delete own job ────────────────────────────────────────────────────────
    @DeleteMapping("/api/jobs/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteJob(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        int deleted = jdbc.update("DELETE FROM jobs WHERE id = ? AND user_id = ?", id, uid);
        if (deleted == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Job not found or not yours."));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Map<String, Object> getJobById(String id, String uid) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT j.id, j.user_id, j.title, j.company, j.location, j.type,
                           j.description, j.skills, j.salary, j.created_at,
                           u.first_name, u.last_name,
                           (SELECT COUNT(*) FROM job_saves       s WHERE s.job_id = j.id AND s.user_id = ?)   AS saved_by_me,
                           (SELECT COUNT(*) FROM job_applications a WHERE a.job_id = j.id AND a.applicant_id = ?) AS applied_by_me,
                           (SELECT COUNT(*) FROM job_applications a WHERE a.job_id = j.id)                    AS applicant_count
                    FROM jobs j JOIN users u ON u.id = j.user_id
                    WHERE j.id = ?
                    """, uid, uid, id);
            if (rows.isEmpty()) return null;
            return toJobList(rows, uid).get(0);
        } catch (Exception e) { return null; }
    }

    private List<Map<String, Object>> toJobList(List<Map<String, Object>> rows, String uid) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("id",          r.get("id"));
            j.put("userId",      r.get("user_id"));
            j.put("title",       s(r, "title"));
            j.put("company",     s(r, "company"));
            j.put("location",    s(r, "location"));
            j.put("type",        s(r, "type"));
            j.put("description", s(r, "description"));
            j.put("skills",      s(r, "skills"));
            j.put("salary",      s(r, "salary"));
            j.put("createdAt",   s(r, "created_at"));
            j.put("posterName",     (s(r,"first_name") + " " + s(r,"last_name")).trim());
            j.put("savedByMe",      toLong(r.get("saved_by_me")) > 0);
            j.put("appliedByMe",    toLong(r.get("applied_by_me")) > 0);
            j.put("applicantCount", toLong(r.get("applicant_count")));
            j.put("isMine",         uid.equals(r.get("user_id")));
            result.add(j);
        }
        return result;
    }

    private static String clean(Map<String, Object> b, String k) {
        Object v = b.get(k); return v == null ? "" : v.toString().trim();
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
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
