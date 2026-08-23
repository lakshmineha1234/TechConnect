package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GET    /api/certifications/{userId}   list certs for a profile (any logged-in user)
 * POST   /api/certifications            add own cert
 * PUT    /api/certifications/{id}       update own cert
 * DELETE /api/certifications/{id}       delete own cert
 */
@RestController
@RequestMapping("/api/certifications")
public class CertificationController {

    private final JdbcTemplate jdbc;

    public CertificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable String userId, HttpSession session) {

        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, name, issuer, issue_date, expiry_date, credential_url, display_order
            FROM certifications
            WHERE user_id = ?
            ORDER BY display_order, created_at
            """, userId);

        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",            r.get("id"));
            m.put("name",          s(r, "name"));
            m.put("issuer",        s(r, "issuer"));
            m.put("issueDate",     s(r, "issue_date"));
            m.put("expiryDate",    s(r, "expiry_date"));
            m.put("credentialUrl", s(r, "credential_url"));
            m.put("displayOrder",  r.get("display_order"));
            return m;
        }).collect(Collectors.toList()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(
            @RequestBody Map<String, Object> body, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        String name = s(body, "name").strip();
        if (name.isEmpty())      return err(400, "Certification name is required.");
        if (name.length() > 120) return err(400, "Name too long (max 120 chars).");

        String issuer        = truncate(s(body, "issuer"),        120);
        String issueDate     = truncate(s(body, "issueDate"),      10);
        String expiryDate    = truncate(s(body, "expiryDate"),     10);
        String credentialUrl = truncate(s(body, "credentialUrl"), 500);

        Integer maxOrder = jdbc.queryForObject(
            "SELECT COALESCE(MAX(display_order),0) FROM certifications WHERE user_id=?",
            Integer.class, me);
        int order = (maxOrder == null ? 0 : maxOrder) + 1;

        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO certifications
                (id, user_id, name, issuer, issue_date, expiry_date, credential_url, display_order)
            VALUES (?,?,?,?,?,?,?,?)
            """, id, me, name, issuer, issueDate, expiryDate, credentialUrl, order);

        return ResponseEntity.ok(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        String name = s(body, "name").strip();
        if (name.isEmpty())      return err(400, "Certification name is required.");
        if (name.length() > 120) return err(400, "Name too long (max 120 chars).");

        int updated = jdbc.update("""
            UPDATE certifications
            SET name=?, issuer=?, issue_date=?, expiry_date=?, credential_url=?
            WHERE id=? AND user_id=?
            """,
            name,
            truncate(s(body, "issuer"),        120),
            truncate(s(body, "issueDate"),      10),
            truncate(s(body, "expiryDate"),     10),
            truncate(s(body, "credentialUrl"), 500),
            id, me);

        if (updated == 0) return err(404, "Certification not found or not yours.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id, HttpSession session) {

        String me = (String) session.getAttribute("userId");
        if (me == null) return err(401, "Not authenticated.");

        int deleted = jdbc.update(
            "DELETE FROM certifications WHERE id=? AND user_id=?", id, me);
        if (deleted == 0) return err(404, "Certification not found or not yours.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String s(Map<?, ?> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
