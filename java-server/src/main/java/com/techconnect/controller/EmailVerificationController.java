package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/api/auth")
public class EmailVerificationController {

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate ws;

    @Value("${techconnect.app-url:http://localhost:8080}")
    private String appUrl;

    public EmailVerificationController(JdbcTemplate jdbc, SimpMessagingTemplate ws) {
        this.jdbc = jdbc;
        this.ws   = ws;
    }

    // GET /api/auth/verify-email?token=xxx
    @GetMapping("/verify-email")
    public RedirectView verifyEmail(
            @RequestParam(required = false) String token,
            HttpSession session) {

        if (token == null || token.isBlank()) {
            return new RedirectView(appUrl + "/?verify=invalid");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, first_name, last_name, email, role FROM users WHERE verify_token = ?", token);

        if (rows.isEmpty()) {
            return new RedirectView(appUrl + "/?verify=invalid");
        }

        Map<String, Object> user = rows.get(0);
        String id = (String) user.get("id");

        jdbc.update(
            "UPDATE users SET email_verified = TRUE, verify_token = NULL WHERE id = ?", id);

        // Log the user in automatically
        session.setAttribute("userId", id);

        // Broadcast new member join now that they're verified
        try {
            String fn = str(user, "first_name");
            String ln = str(user, "last_name");
            Map<String, Object> broadcast = new java.util.LinkedHashMap<>();
            broadcast.put("id",          id);
            broadcast.put("name",        (fn + " " + ln).trim());
            broadcast.put("firstName",   fn);
            broadcast.put("lastName",    ln);
            broadcast.put("role",        user.get("role"));
            broadcast.put("institution", "");
            broadcast.put("company",     "");
            broadcast.put("bio",         "");
            broadcast.put("location",    "");
            broadcast.put("jobTitle",    "");
            broadcast.put("skills",      List.of());
            broadcast.put("joinedAt",    java.time.Instant.now().toString());
            broadcast.put("connectionCount", 0);
            broadcast.put("connectedWithMe", false);
            broadcast.put("pendingWithMe",   false);
            ws.convertAndSend("/topic/new-member", broadcast);
        } catch (Exception ignored) {}

        return new RedirectView(appUrl + "/?verify=success");
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
}
