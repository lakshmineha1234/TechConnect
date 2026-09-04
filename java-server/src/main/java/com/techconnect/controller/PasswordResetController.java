package com.techconnect.controller;

import com.techconnect.service.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.techconnect.controller.AuthController.err;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final JdbcTemplate jdbc;
    private final EmailService emailService;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @Value("${techconnect.app-url:http://localhost:8080}")
    private String appUrl;

    public PasswordResetController(JdbcTemplate jdbc, EmailService emailService) {
        this.jdbc         = jdbc;
        this.emailService = emailService;
    }

    // POST /api/auth/forgot-password  { email }
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(
            @RequestBody Map<String, Object> body) {

        String email = body == null ? "" : body.getOrDefault("email", "").toString().trim().toLowerCase();
        if (email.isEmpty()) return err(400, "Email is required.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, first_name FROM users WHERE email=?", email);

        // Always return success to prevent email enumeration
        if (rows.isEmpty())
            return ResponseEntity.ok(Map.of("ok", true, "message", "If that email is registered, a reset link has been sent."));

        Map<String, Object> user = rows.get(0);
        String uid       = (String) user.get("id");
        String firstName = (String) user.get("first_name");

        // Delete any existing tokens for this user
        jdbc.update("DELETE FROM password_reset_tokens WHERE user_id=?", uid);

        String token    = UUID.randomUUID().toString();
        String expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        jdbc.update("INSERT INTO password_reset_tokens (token, user_id, expires_at) VALUES (?,?,?)",
                token, uid, expiresAt);

        String resetLink = appUrl + "/?reset=" + token;

        // Try to send email if SMTP is configured; always return the link for dev/demo use
        boolean emailSent = false;
        if (fromEmail != null && !fromEmail.isBlank()) {
            sendResetEmail(email, firstName, resetLink);
            emailSent = true;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("resetLink", resetLink);
        resp.put("emailSent", emailSent);
        return ResponseEntity.ok(resp);
    }

    // POST /api/auth/reset-password  { token, password }
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @RequestBody Map<String, Object> body) {

        String token    = body == null ? "" : body.getOrDefault("token",    "").toString().trim();
        String password = body == null ? "" : body.getOrDefault("password", "").toString();

        if (token.isEmpty())    return err(400, "Token is required.");
        if (password.length() < 6) return err(400, "Password must be at least 6 characters.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT user_id, expires_at FROM password_reset_tokens WHERE token=?", token);

        if (rows.isEmpty()) return err(400, "Invalid or expired reset link.");

        Map<String, Object> row = rows.get(0);
        String expiresAt = (String) row.get("expires_at");

        try {
            if (Instant.parse(expiresAt).isBefore(Instant.now()))
                return err(400, "Reset link has expired. Please request a new one.");
        } catch (Exception e) {
            return err(400, "Invalid reset link.");
        }

        String uid  = (String) row.get("user_id");
        String hash = bcrypt.encode(password);
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, uid);
        jdbc.update("DELETE FROM password_reset_tokens WHERE token=?", token);

        return ResponseEntity.ok(Map.of("ok", true, "message", "Password updated successfully. You can now log in."));
    }

    void sendResetEmail(String to, String firstName, String resetLink) {
        emailService.send(
            to,
            "Reset your TechConnect password",
            "Hi " + firstName + ",\n\n" +
            "We received a request to reset your TechConnect password.\n" +
            "Click the link below to set a new password. This link expires in 1 hour.\n\n" +
            resetLink + "\n\n" +
            "If you didn't request this, you can safely ignore this email.\n\n" +
            "— The TechConnect Team"
        );
    }
}
