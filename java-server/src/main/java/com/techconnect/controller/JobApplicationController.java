package com.techconnect.controller;

import com.techconnect.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Job Applications
 *
 * POST   /api/jobs/{id}/apply                apply to a job (with optional cover note)
 * DELETE /api/jobs/{id}/apply                withdraw application
 * GET    /api/jobs/{id}/applicants           list applicants for own job posting
 * PATCH  /api/jobs/{id}/applicants/{appId}   update application status (poster only)
 * GET    /api/applications/mine              my submitted applications
 * GET    /api/applications/stats             { applied, pending, accepted } for dashboard
 */
@RestController
public class JobApplicationController {

    private static final Set<String> VALID_STATUSES = Set.of("pending","reviewed","accepted","rejected");

    private final JdbcTemplate      jdbc;
    private final NotificationService notifSvc;

    public JobApplicationController(JdbcTemplate jdbc, NotificationService notifSvc) {
        this.jdbc     = jdbc;
        this.notifSvc = notifSvc;
    }

    // ── Apply ─────────────────────────────────────────────────────────────────
    @PostMapping("/api/jobs/{id}/apply")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> apply(
            @PathVariable String id,
            @RequestBody(required = false) Map<String,Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        // Check job exists
        List<Map<String,Object>> job = jdbc.queryForList(
            "SELECT id, user_id, title, company FROM jobs WHERE id=?", id);
        if (job.isEmpty()) return err(404, "Job not found.");
        String posterId = s(job.get(0), "user_id");

        if (uid.equals(posterId)) return err(400, "You cannot apply to your own job posting.");

        // Check not already applied
        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_applications WHERE job_id=? AND applicant_id=?",
            Integer.class, id, uid);
        if (already != null && already > 0) return err(409, "You have already applied to this job.");

        String note = "";
        if (body != null && body.get("coverNote") != null) {
            note = body.get("coverNote").toString().trim();
            if (note.length() > 500) return err(400, "Cover note too long (max 500 chars).");
        }

        String appId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO job_applications (id, job_id, applicant_id, cover_note, status)
            VALUES (?,?,?,?,'pending')
            """, appId, id, uid, note);

        // Notify the job poster
        notifSvc.create(posterId, "job_application", uid, id);

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("id",        appId);
        resp.put("jobId",     id);
        resp.put("jobTitle",  s(job.get(0),"title"));
        resp.put("company",   s(job.get(0),"company"));
        resp.put("coverNote", note);
        resp.put("status",    "pending");
        resp.put("createdAt", java.time.Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    // ── Withdraw ──────────────────────────────────────────────────────────────
    @DeleteMapping("/api/jobs/{id}/apply")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> withdraw(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        int del = jdbc.update(
            "DELETE FROM job_applications WHERE job_id=? AND applicant_id=?", id, uid);
        if (del == 0) return err(404, "Application not found.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── List applicants (poster view) ─────────────────────────────────────────
    @GetMapping("/api/jobs/{id}/applicants")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> applicants(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        // Must be the job poster
        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM jobs WHERE id=? AND user_id=?", Integer.class, id, uid);
        if (owns == null || owns == 0)
            return ResponseEntity.status(403).body(null);

        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT a.id, a.applicant_id, a.cover_note, a.status, a.created_at, a.updated_at,
                   u.first_name, u.last_name, u.role,
                   p.bio, p.location, p.company, p.institution, p.job_title
            FROM job_applications a
            JOIN users u ON u.id = a.applicant_id
            LEFT JOIN profiles p ON p.user_id = a.applicant_id
            WHERE a.job_id = ?
            ORDER BY a.created_at ASC
            """, id);

        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            Map<String,Object> app = new LinkedHashMap<>();
            app.put("id",          r.get("id"));
            app.put("applicantId", s(r,"applicant_id"));
            app.put("name",        (s(r,"first_name")+" "+s(r,"last_name")).trim());
            app.put("firstName",   s(r,"first_name"));
            app.put("lastName",    s(r,"last_name"));
            app.put("role",        s(r,"role"));
            app.put("bio",         s(r,"bio"));
            app.put("location",    s(r,"location"));
            app.put("company",     s(r,"company"));
            app.put("institution", s(r,"institution"));
            app.put("jobTitle",    s(r,"job_title"));
            app.put("coverNote",   s(r,"cover_note"));
            app.put("status",      s(r,"status"));
            app.put("createdAt",   s(r,"created_at"));
            app.put("updatedAt",   s(r,"updated_at"));
            result.add(app);
        }
        return ResponseEntity.ok(result);
    }

    // ── Update application status (poster only) ───────────────────────────────
    @PatchMapping("/api/jobs/{id}/applicants/{appId}")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> updateStatus(
            @PathVariable String id,
            @PathVariable String appId,
            @RequestBody Map<String,Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String newStatus = body.get("status") == null ? "" : body.get("status").toString().trim();
        if (!VALID_STATUSES.contains(newStatus)) return err(400, "Invalid status.");

        // Verify caller owns the job
        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM jobs j JOIN job_applications a ON a.job_id=j.id " +
            "WHERE a.id=? AND j.id=? AND j.user_id=?",
            Integer.class, appId, id, uid);
        if (owns == null || owns == 0)
            return err(404, "Application not found or not your job.");

        jdbc.update(
            "UPDATE job_applications SET status=?, updated_at=datetime('now') WHERE id=?",
            newStatus, appId);

        // Notify the applicant
        String applicantId = null;
        try {
            applicantId = jdbc.queryForObject(
                "SELECT applicant_id FROM job_applications WHERE id=?", String.class, appId);
        } catch (Exception ignored) {}
        if (applicantId != null) {
            notifSvc.create(applicantId, "application_status", uid, id);
        }

        return ResponseEntity.ok(Map.of("ok", true, "status", newStatus));
    }

    // ── My applications (applicant view) ─────────────────────────────────────
    @GetMapping("/api/applications/mine")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> myApplications(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT a.id, a.job_id, a.cover_note, a.status, a.created_at, a.updated_at,
                   j.title, j.company, j.location, j.type
            FROM job_applications a
            JOIN jobs j ON j.id = a.job_id
            WHERE a.applicant_id = ?
            ORDER BY a.created_at DESC
            """, uid);

        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            Map<String,Object> app = new LinkedHashMap<>();
            app.put("id",        r.get("id"));
            app.put("jobId",     s(r,"job_id"));
            app.put("jobTitle",  s(r,"title"));
            app.put("company",   s(r,"company"));
            app.put("location",  s(r,"location"));
            app.put("jobType",   s(r,"type"));
            app.put("coverNote", s(r,"cover_note"));
            app.put("status",    s(r,"status"));
            app.put("createdAt", s(r,"created_at"));
            app.put("updatedAt", s(r,"updated_at"));
            result.add(app);
        }
        return ResponseEntity.ok(result);
    }

    // ── Stats (dashboard) ─────────────────────────────────────────────────────
    @GetMapping("/api/applications/stats")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> stats(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Long applied  = jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_applications WHERE applicant_id=?", Long.class, uid);
        Long pending  = jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_applications WHERE applicant_id=? AND status='pending'", Long.class, uid);
        Long accepted = jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_applications WHERE applicant_id=? AND status='accepted'", Long.class, uid);
        // As poster: applications to my jobs
        Long received = jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_applications a JOIN jobs j ON j.id=a.job_id WHERE j.user_id=?", Long.class, uid);

        return ResponseEntity.ok(Map.of(
            "applied",  applied  == null ? 0 : applied,
            "pending",  pending  == null ? 0 : pending,
            "accepted", accepted == null ? 0 : accepted,
            "received", received == null ? 0 : received
        ));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String s(Map<String,Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static ResponseEntity<Map<String,Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
