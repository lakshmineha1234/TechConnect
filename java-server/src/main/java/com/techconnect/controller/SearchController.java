package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GET /api/search?q=&type=all|people|posts|jobs
 * Returns up to 10 results per category (or all three if type=all).
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final JdbcTemplate jdbc;

    public SearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "all") String type,
            HttpSession session) {

        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        String raw = q.strip();
        if (raw.length() > 100) raw = raw.substring(0, 100);
        if (raw.isEmpty()) return ResponseEntity.ok(Map.of("people", List.of(), "posts", List.of(), "jobs", List.of()));

        String like = "%" + raw.toLowerCase() + "%";

        Map<String, Object> result = new LinkedHashMap<>();

        if ("all".equals(type) || "people".equals(type)) {
            result.put("people", searchPeople(raw, like));
        }
        if ("all".equals(type) || "posts".equals(type)) {
            result.put("posts", searchPosts(raw, like));
        }
        if ("all".equals(type) || "jobs".equals(type)) {
            result.put("jobs", searchJobs(raw, like));
        }

        return ResponseEntity.ok(result);
    }

    private List<Map<String, Object>> searchPeople(String raw, String like) {
        return jdbc.queryForList("""
            SELECT u.id, u.first_name, u.last_name, u.role,
                   p.job_title, p.company, p.institution, p.location
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE lower(u.first_name || ' ' || u.last_name) LIKE ?
               OR lower(COALESCE(p.job_title,''))   LIKE ?
               OR lower(COALESCE(p.company,''))      LIKE ?
               OR lower(COALESCE(p.institution,''))  LIKE ?
               OR lower(COALESCE(p.bio,''))          LIKE ?
            ORDER BY
              CASE WHEN lower(u.first_name || ' ' || u.last_name) LIKE ? THEN 0 ELSE 1 END,
              u.first_name, u.last_name
            LIMIT 10
            """, like, like, like, like, like, like)
            .stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",       r.get("id"));
                m.put("name",     (s(r,"first_name") + " " + s(r,"last_name")).trim());
                m.put("role",     r.get("role"));
                m.put("jobTitle", s(r,"job_title").isEmpty() ? s(r,"institution") : s(r,"job_title"));
                m.put("subtitle", buildPeopleSubtitle(r));
                return m;
            }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> searchPosts(String raw, String like) {
        return jdbc.queryForList("""
            SELECT p.id, p.content, p.created_at, u.first_name, u.last_name
            FROM posts p
            JOIN users u ON u.id = p.user_id
            WHERE lower(p.content) LIKE ?
            ORDER BY p.created_at DESC
            LIMIT 10
            """, like)
            .stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         r.get("id"));
                m.put("authorName", (s(r,"first_name") + " " + s(r,"last_name")).trim());
                m.put("content",    excerpt(s(r,"content"), 160));
                m.put("createdAt",  r.get("created_at"));
                return m;
            }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> searchJobs(String raw, String like) {
        return jdbc.queryForList("""
            SELECT j.id, j.title, j.company, j.location, j.type, j.salary, j.created_at
            FROM jobs j
            WHERE lower(j.title)       LIKE ?
               OR lower(j.company)     LIKE ?
               OR lower(j.description) LIKE ?
               OR lower(j.skills)      LIKE ?
               OR lower(j.location)    LIKE ?
            ORDER BY j.created_at DESC
            LIMIT 10
            """, like, like, like, like, like)
            .stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",       r.get("id"));
                m.put("title",    r.get("title"));
                m.put("company",  r.get("company"));
                m.put("location", s(r,"location"));
                m.put("type",     r.get("type"));
                m.put("salary",   s(r,"salary"));
                return m;
            }).collect(Collectors.toList());
    }

    private static String buildPeopleSubtitle(Map<String, Object> r) {
        String title    = s(r, "job_title");
        String company  = s(r, "company");
        String inst     = s(r, "institution");
        String location = s(r, "location");
        String role     = s(r, "role");

        String line1 = !title.isEmpty() && !company.isEmpty() ? title + " at " + company
                     : !title.isEmpty()   ? title
                     : !company.isEmpty() ? company
                     : !inst.isEmpty()    ? inst
                     : role.equals("student") ? "Student" : "IT Professional";
        return location.isEmpty() ? line1 : line1 + " · " + location;
    }

    private static String excerpt(String text, int max) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }

    private static String s(Map<?, ?> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
}
