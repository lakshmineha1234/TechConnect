package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * POST   /api/profile/resume          upload PDF (students only, max 5 MB)
 * GET    /api/profile/resume/{userId} download PDF (self or connected users)
 * DELETE /api/profile/resume          remove own resume
 * GET    /api/profile/resume/{userId}/info  metadata (name, size)
 */
@RestController
public class ResumeController {

    private final JdbcTemplate jdbc;
    private final Path resumeDir;

    public ResumeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        Path here   = Paths.get("").toAbsolutePath();
        Path parent = here.getParent() != null ? here.getParent() : here;
        this.resumeDir = parent.resolve("uploads").resolve("resumes");
        try { Files.createDirectories(this.resumeDir); } catch (Exception ignored) {}
    }

    // ── Upload ────────────────────────────────────────────────────────────────
    @PostMapping("/api/profile/resume")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String role = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, uid);
        if (!"student".equals(role)) return err(403, "Only students can upload a resume.");

        if (file == null || file.isEmpty()) return err(400, "No file provided.");

        String mime = file.getContentType();
        if (!"application/pdf".equalsIgnoreCase(mime))
            return err(400, "Only PDF files are allowed.");

        if (file.getSize() > 5 * 1024 * 1024)
            return err(400, "Resume must be under 5 MB.");

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) originalName = "resume.pdf";
        // Sanitise — keep only safe characters
        originalName = originalName.replaceAll("[^a-zA-Z0-9._\\- ]", "_");

        try {
            Path dest = resumeDir.resolve(uid + ".pdf");
            Files.write(dest, file.getBytes());

            jdbc.update("""
                INSERT INTO profiles (user_id, resume_name) VALUES (?, ?)
                ON CONFLICT(user_id) DO UPDATE SET resume_name = excluded.resume_name
                """, uid, originalName);

            return ResponseEntity.ok(Map.of("ok", true, "name", originalName, "size", file.getSize()));
        } catch (Exception e) {
            return err(500, "Upload failed: " + e.getMessage());
        }
    }

    // ── Serve ─────────────────────────────────────────────────────────────────
    @GetMapping("/api/profile/resume/{userId}")
    public ResponseEntity<byte[]> serve(
            @PathVariable String userId,
            HttpSession session) {

        String viewerId = (String) session.getAttribute("userId");
        if (viewerId == null) return ResponseEntity.status(401).build();

        if (!userId.matches("[a-zA-Z0-9\\-]{1,64}"))
            return ResponseEntity.badRequest().build();

        // Must be own resume, or connected to the student
        if (!viewerId.equals(userId)) {
            List<Map<String, Object>> conn = jdbc.queryForList("""
                SELECT id FROM connections
                WHERE ((requester_id = ? AND recipient_id = ?)
                    OR (requester_id = ? AND recipient_id = ?))
                  AND status = 'accepted'
                """, viewerId, userId, userId, viewerId);
            if (conn.isEmpty()) return ResponseEntity.status(403).build();
        }

        try {
            String name = null;
            try {
                name = jdbc.queryForObject(
                    "SELECT resume_name FROM profiles WHERE user_id = ?",
                    String.class, userId);
            } catch (Exception ignored) {}

            if (name == null || name.isBlank()) return ResponseEntity.notFound().build();

            Path file = resumeDir.resolve(userId + ".pdf");
            if (!Files.exists(file)) return ResponseEntity.notFound().build();

            byte[] bytes = Files.readAllBytes(file);
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded);
            headers.setCacheControl("private, max-age=3600");
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // ── Info (filename + size) ────────────────────────────────────────────────
    @GetMapping("/api/profile/resume/{userId}/info")
    public ResponseEntity<Map<String, Object>> info(
            @PathVariable String userId,
            HttpSession session) {

        String viewerId = (String) session.getAttribute("userId");
        if (viewerId == null) return ResponseEntity.status(401).build();

        if (!userId.matches("[a-zA-Z0-9\\-]{1,64}"))
            return ResponseEntity.badRequest().build();

        try {
            String name = null;
            try {
                name = jdbc.queryForObject(
                    "SELECT resume_name FROM profiles WHERE user_id = ?",
                    String.class, userId);
            } catch (Exception ignored) {}

            if (name == null || name.isBlank()) return ResponseEntity.notFound().build();

            Path file = resumeDir.resolve(userId + ".pdf");
            if (!Files.exists(file)) return ResponseEntity.notFound().build();

            long size = Files.size(file);
            return ResponseEntity.ok(Map.of("name", name, "size", size));
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    @DeleteMapping("/api/profile/resume")
    public ResponseEntity<Map<String, Object>> delete(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        try {
            Files.deleteIfExists(resumeDir.resolve(uid + ".pdf"));
            jdbc.update("UPDATE profiles SET resume_name = '' WHERE user_id = ?", uid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return err(500, "Delete failed.");
        }
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
