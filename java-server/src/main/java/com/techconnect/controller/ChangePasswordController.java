package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.techconnect.controller.AuthController.err;
import static com.techconnect.controller.AuthController.ok;

/**
 * POST /api/auth/change-password  { currentPassword, newPassword }
 * Lets a logged-in user update their password after confirming the current one.
 * Social-login users (no password_hash) cannot use this endpoint.
 */
@RestController
@RequestMapping("/api/auth")
public class ChangePasswordController {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public ChangePasswordController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String currentPassword = body == null ? "" : body.getOrDefault("currentPassword", "").toString();
        String newPassword     = body == null ? "" : body.getOrDefault("newPassword",     "").toString();

        if (currentPassword.isBlank()) return err(400, "Current password is required.");
        if (newPassword.length() < 6)  return err(400, "New password must be at least 6 characters.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT password_hash FROM users WHERE id = ?", uid);
        if (rows.isEmpty()) return err(404, "User not found.");

        String storedHash = (String) rows.get(0).get("password_hash");
        if (storedHash == null || storedHash.isBlank())
            return err(400, "Social login accounts cannot use password change. Log in via your provider.");

        if (!bcrypt.matches(currentPassword, storedHash))
            return err(401, "Current password is incorrect.");

        String newHash = bcrypt.encode(newPassword);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", newHash, uid);

        return ok(Map.of("ok", true));
    }
}
