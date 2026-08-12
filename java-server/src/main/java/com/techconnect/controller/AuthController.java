package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

    public AuthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── POST /api/auth/register ───────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String firstName   = clean(body, "firstName");
        String lastName    = clean(body, "lastName");
        String email       = clean(body, "email").toLowerCase();
        String password    = raw(body, "password");
        String role        = raw(body, "role");
        String institution = clean(body, "institution");
        String company     = clean(body, "company");

        if (firstName.isEmpty() || email.isEmpty() || password.isEmpty()
                || (!role.equals("student") && !role.equals("pro"))) {
            return err(400, "Missing required fields.");
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        if (count != null && count > 0) {
            return err(409, "An account with that email already exists.");
        }

        String id   = UUID.randomUUID().toString();
        String hash = bcrypt.encode(password);

        jdbc.update(
            "INSERT INTO users (id,email,password_hash,role,first_name,last_name) VALUES(?,?,?,?,?,?)",
            id, email, hash, role, firstName, lastName);

        jdbc.update(
            "INSERT INTO profiles (user_id,institution,company) VALUES(?,?,?)",
            id, institution, company);

        session.setAttribute("userId", id);
        return ResponseEntity.status(201).body(Map.of("user", buildUser(id)));
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String email    = clean(body, "email").toLowerCase();
        String password = raw(body, "password");

        if (email.isEmpty() || password.isEmpty()) {
            return err(400, "Email and password are required.");
        }

        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM users WHERE email = ?", email);

        if (rows.isEmpty() || !bcrypt.matches(password, (String) rows.get(0).get("password_hash"))) {
            return err(401, "Invalid email or password.");
        }

        Map<String, Object> user = rows.get(0);
        session.setAttribute("userId", user.get("id"));
        return ok(Map.of("user", formatUser(user)));
    }

    // ── POST /api/auth/logout ─────────────────────────────────────────────────

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ok(Map.of("ok", true));
    }

    // ── GET /api/auth/me ──────────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM users WHERE id = ?", uid);
        if (rows.isEmpty()) {
            session.invalidate();
            return err(401, "User not found.");
        }

        return ok(Map.of("user", formatUser(rows.get(0))));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildUser(String id) {
        return formatUser(jdbc.queryForList("SELECT * FROM users WHERE id = ?", id).get(0));
    }

    static Map<String, Object> formatUser(Map<String, Object> row) {
        String fn   = str(row, "first_name");
        String ln   = str(row, "last_name");
        String name = (fn + " " + ln).trim();
        if (name.isEmpty()) name = str(row, "email");
        return Map.of(
            "id",        row.get("id"),
            "email",     row.get("email"),
            "role",      row.get("role"),
            "firstName", fn,
            "lastName",  ln,
            "name",      name
        );
    }

    // Extract and trim a string field; returns "" if absent/null
    static String clean(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    // Extract raw string (no trim); returns "" if absent/null
    static String raw(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    // Safe toString for a db row value
    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    static ResponseEntity<Map<String, Object>> err(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }
}
