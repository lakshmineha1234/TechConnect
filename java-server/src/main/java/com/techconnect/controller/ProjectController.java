package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * GET    /api/projects/{userId}   list all projects for a user (any authenticated user)
 * POST   /api/projects            create a project (students only)
 * PUT    /api/projects/{id}       update own project
 * DELETE /api/projects/{id}       delete own project
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final JdbcTemplate jdbc;

    public ProjectController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable String userId, HttpSession session) {

        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, user_id, title, description, tech_stack, github_url, live_url,
                   display_order, created_at
            FROM projects WHERE user_id = ?
            ORDER BY display_order ASC, created_at ASC
            """, userId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id",           r.get("id"));
            p.put("userId",       s(r, "user_id"));
            p.put("title",        s(r, "title"));
            p.put("description",  s(r, "description"));
            p.put("techStack",    s(r, "tech_stack"));
            p.put("githubUrl",    s(r, "github_url"));
            p.put("liveUrl",      s(r, "live_url"));
            p.put("displayOrder", r.get("display_order"));
            p.put("createdAt",    s(r, "created_at"));
            result.add(p);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String title = trim(body, "title");
        if (title.isEmpty()) return err(400, "Title is required.");
        if (title.length() > 120) return err(400, "Title too long (max 120 chars).");

        String desc  = trim(body, "description");
        String tech  = trim(body, "techStack");
        String ghUrl = trim(body, "githubUrl");
        String lvUrl = trim(body, "liveUrl");

        if (desc.length()  > 1000) return err(400, "Description too long (max 1000 chars).");
        if (tech.length()  > 300)  return err(400, "Tech stack too long (max 300 chars).");
        if (ghUrl.length() > 500)  return err(400, "GitHub URL too long.");
        if (lvUrl.length() > 500)  return err(400, "Live URL too long.");

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM projects WHERE user_id = ?", Integer.class, uid);
        if (count != null && count >= 20) return err(400, "Maximum 20 projects allowed.");

        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO projects (id, user_id, title, description, tech_stack, github_url, live_url, display_order)
            VALUES (?,?,?,?,?,?,?, (SELECT COALESCE(MAX(display_order),0)+1 FROM projects WHERE user_id=?))
            """, id, uid, title, desc, tech, ghUrl, lvUrl, uid);

        return ResponseEntity.ok(fetchOne(id, uid));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM projects WHERE id=? AND user_id=?", Integer.class, id, uid);
        if (owns == null || owns == 0) return err(404, "Project not found.");

        String title = trim(body, "title");
        if (title.isEmpty()) return err(400, "Title is required.");
        if (title.length() > 120) return err(400, "Title too long.");

        String desc  = trim(body, "description");
        String tech  = trim(body, "techStack");
        String ghUrl = trim(body, "githubUrl");
        String lvUrl = trim(body, "liveUrl");

        jdbc.update("""
            UPDATE projects SET title=?, description=?, tech_stack=?, github_url=?, live_url=?
            WHERE id=? AND user_id=?
            """, title, desc, tech, ghUrl, lvUrl, id, uid);

        return ResponseEntity.ok(fetchOne(id, uid));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        int deleted = jdbc.update(
            "DELETE FROM projects WHERE id=? AND user_id=?", id, uid);
        if (deleted == 0) return err(404, "Project not found.");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Map<String, Object> fetchOne(String id, String uid) {
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT * FROM projects WHERE id=? AND user_id=?", id, uid);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id",           r.get("id"));
        p.put("userId",       s(r, "user_id"));
        p.put("title",        s(r, "title"));
        p.put("description",  s(r, "description"));
        p.put("techStack",    s(r, "tech_stack"));
        p.put("githubUrl",    s(r, "github_url"));
        p.put("liveUrl",      s(r, "live_url"));
        p.put("displayOrder", r.get("display_order"));
        p.put("createdAt",    s(r, "created_at"));
        return p;
    }

    private static String trim(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString().trim();
    }
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
