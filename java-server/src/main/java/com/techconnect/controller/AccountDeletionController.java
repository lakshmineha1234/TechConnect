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
 * DELETE /api/auth/account  { password }
 * Permanently deletes the authenticated user's account.
 * All related data is removed via ON DELETE CASCADE on the DB side.
 */
@RestController
@RequestMapping("/api/auth")
public class AccountDeletionController {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AccountDeletionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @DeleteMapping("/account")
    public ResponseEntity<Map<String, Object>> deleteAccount(
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT password_hash FROM users WHERE id=?", uid);
        if (rows.isEmpty()) return err(404, "User not found.");

        String storedHash = (String) rows.get(0).get("password_hash");

        // Social-login users have no password — allow deletion without one.
        // Email/password users must confirm their password.
        if (storedHash != null && !storedHash.isBlank()) {
            String password = body == null ? "" : body.getOrDefault("password", "").toString();
            if (password.isBlank()) return err(400, "Password is required to delete your account.");
            if (!bcrypt.matches(password, storedHash))
                return err(401, "Incorrect password.");
        }

        // Delete user — all related rows cascade automatically
        jdbc.update("DELETE FROM users WHERE id=?", uid);

        session.invalidate();
        return ok(Map.of("ok", true));
    }
}
