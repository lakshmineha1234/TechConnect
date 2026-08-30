package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate ws;
    private final JavaMailSender mailer;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

    @Value("${spring.mail.username:}")  private String fromEmail;
    @Value("${techconnect.app-url:http://localhost:8080}") private String appUrl;

    public AuthController(JdbcTemplate jdbc, SimpMessagingTemplate ws, JavaMailSender mailer) {
        this.jdbc   = jdbc;
        this.ws     = ws;
        this.mailer = mailer;
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

        String id    = UUID.randomUUID().toString();
        String hash  = bcrypt.encode(password);
        String token = UUID.randomUUID().toString();

        jdbc.update(
            "INSERT INTO users (id,email,password_hash,role,first_name,last_name,email_verified,verify_token) VALUES(?,?,?,?,?,?,FALSE,?)",
            id, email, hash, role, firstName, lastName, token);

        jdbc.update(
            "INSERT INTO profiles (user_id,institution,company) VALUES(?,?,?)",
            id, institution, company);

        // Send verification email
        sendVerificationEmail(email, firstName, token);

        return ResponseEntity.status(201).body(Map.of(
            "needsVerification", true,
            "email", email
        ));
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

        // Block login if email not verified
        Object verified = user.get("email_verified");
        boolean isVerified = verified instanceof Boolean b ? b
            : verified instanceof Number n ? n.intValue() != 0
            : "true".equalsIgnoreCase(String.valueOf(verified));
        if (!isVerified) {
            return ResponseEntity.status(403).body(Map.of(
                "error", "email_not_verified",
                "email", email
            ));
        }

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

    // ── POST /api/auth/resend-verification ───────────────────────────────────

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(
            @RequestBody Map<String, Object> body) {

        String email = clean(body, "email").toLowerCase();
        if (email.isEmpty()) return err(400, "Email required.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, first_name, email_verified FROM users WHERE email = ?", email);
        if (rows.isEmpty()) return ok(Map.of("ok", true)); // don't reveal existence

        Map<String, Object> user = rows.get(0);
        Object verified = user.get("email_verified");
        boolean isVerified = verified instanceof Boolean b ? b
            : verified instanceof Number n ? n.intValue() != 0
            : "true".equalsIgnoreCase(String.valueOf(verified));
        if (isVerified) return ok(Map.of("ok", true)); // already verified, silently OK

        String token = UUID.randomUUID().toString();
        jdbc.update("UPDATE users SET verify_token = ? WHERE email = ?", token, email);
        sendVerificationEmail(email, str(user, "first_name"), token);
        return ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendVerificationEmail(String email, String firstName, String token) {
        try {
            String link = appUrl + "/api/auth/verify-email?token=" + token;
            String name = firstName.isEmpty() ? "there" : firstName;
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(email);
            msg.setSubject("Verify your TechConnect email");
            msg.setText(
                "Hi " + name + ",\n\n" +
                "Thanks for joining TechConnect! Please verify your email address by clicking the link below:\n\n" +
                link + "\n\n" +
                "This link does not expire.\n\n" +
                "If you didn't create an account, you can ignore this email.\n\n" +
                "— The TechConnect Team"
            );
            mailer.send(msg);
        } catch (Exception ignored) {}
    }

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
