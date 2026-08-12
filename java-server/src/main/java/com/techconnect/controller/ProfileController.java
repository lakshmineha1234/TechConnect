package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

import static com.techconnect.controller.AuthController.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final JdbcTemplate jdbc;

    public ProfileController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── GET /api/profile ──────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> getProfile(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        List<Map<String, Object>> userRows =
                jdbc.queryForList("SELECT * FROM users    WHERE id      = ?", uid);
        List<Map<String, Object>> profRows =
                jdbc.queryForList("SELECT * FROM profiles WHERE user_id = ?", uid);

        if (userRows.isEmpty() || profRows.isEmpty()) {
            return err(404, "Profile not found.");
        }

        Map<String, Object> u = userRows.get(0);
        Map<String, Object> p = profRows.get(0);

        List<String> skills = jdbc
                .queryForList("SELECT skill_name FROM skills WHERE user_id = ? ORDER BY id", uid)
                .stream()
                .map(r -> (String) r.get("skill_name"))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("firstName",   str(u, "first_name"));
        result.put("lastName",    str(u, "last_name"));
        result.put("email",       str(u, "email"));
        result.put("role",        str(u, "role"));
        result.put("phone",       str(p, "phone"));
        result.put("location",    str(p, "location"));
        result.put("bio",         str(p, "bio"));
        result.put("institution", str(p, "institution"));
        result.put("degree",      str(p, "degree"));
        result.put("year",        str(p, "year"));
        result.put("company",     str(p, "company"));
        result.put("jobTitle",    str(p, "job_title"));
        result.put("experience",  str(p, "experience"));
        result.put("linkedin",    str(p, "linkedin"));
        result.put("github",      str(p, "github"));
        result.put("skills",      skills);

        return ResponseEntity.ok(result);
    }

    // ── PUT /api/profile ──────────────────────────────────────────────────────

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        List<Map<String, Object>> curRows =
                jdbc.queryForList("SELECT first_name,last_name,email FROM users WHERE id = ?", uid);
        if (curRows.isEmpty()) return err(404, "User not found.");

        Map<String, Object> cur = curRows.get(0);

        // Only overwrite user fields that were explicitly provided
        String fn    = body.containsKey("firstName") ? clean(body, "firstName") : str(cur, "first_name");
        String ln    = body.containsKey("lastName")  ? clean(body, "lastName")  : str(cur, "last_name");
        String email = body.containsKey("email")     ? clean(body, "email").toLowerCase() : str(cur, "email");

        jdbc.update("UPDATE users SET first_name=?, last_name=?, email=? WHERE id=?",
                fn, ln, email, uid);

        jdbc.update("""
                UPDATE profiles
                SET phone=?, location=?, bio=?,
                    institution=?, degree=?, year=?,
                    company=?, job_title=?, experience=?,
                    linkedin=?, github=?,
                    updated_at=datetime('now')
                WHERE user_id=?
                """,
                clean(body, "phone"),       clean(body, "location"),
                clean(body, "bio"),         clean(body, "institution"),
                clean(body, "degree"),      clean(body, "year"),
                clean(body, "company"),     clean(body, "jobTitle"),
                clean(body, "experience"),  clean(body, "linkedin"),
                clean(body, "github"),
                uid);

        // Replace skills list if provided
        if (body.get("skills") instanceof List<?> rawSkills) {
            jdbc.update("DELETE FROM skills WHERE user_id = ?", uid);
            for (Object s : rawSkills) {
                String skill = s.toString().trim();
                if (!skill.isEmpty()) {
                    jdbc.update("INSERT OR IGNORE INTO skills (user_id,skill_name) VALUES(?,?)",
                            uid, skill);
                }
            }
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
