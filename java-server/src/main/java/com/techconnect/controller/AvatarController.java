package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Profile-photo upload and serving — avatars stored in PostgreSQL (BYTEA)
 * so they survive Render redeploys.
 *
 * POST   /api/profile/avatar          upload (multipart "file", max 4 MB, image/* only)
 * GET    /api/profile/avatar/{userId} serve the photo — 404 if none
 * DELETE /api/profile/avatar          remove own photo
 */
@RestController
public class AvatarController {

    private static final Set<String> ALLOWED = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private final JdbcTemplate jdbc;

    public AvatarController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Upload ────────────────────────────────────────────────────────────────
    @PostMapping("/api/profile/avatar")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided."));

        String mime = file.getContentType();
        if (mime == null || !ALLOWED.contains(mime.toLowerCase()))
            return ResponseEntity.badRequest().body(Map.of("error", "Only JPEG, PNG, GIF, or WebP images are allowed."));

        if (file.getSize() > 4 * 1024 * 1024)
            return ResponseEntity.badRequest().body(Map.of("error", "Image must be under 4 MB."));

        try {
            byte[] bytes = file.getBytes();
            jdbc.update("""
                    INSERT INTO profiles (user_id, avatar_mime, avatar_data)
                    VALUES (?, ?, ?)
                    ON CONFLICT(user_id) DO UPDATE
                      SET avatar_mime = excluded.avatar_mime,
                          avatar_data = excluded.avatar_data
                    """, uid, mime.toLowerCase(), bytes);
            return ResponseEntity.ok(Map.of("ok", true, "mime", mime.toLowerCase()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    // ── Serve ─────────────────────────────────────────────────────────────────
    @GetMapping("/api/profile/avatar/{userId}")
    public ResponseEntity<byte[]> serve(@PathVariable String userId) {
        if (!userId.matches("[a-zA-Z0-9\\-]{1,64}"))
            return ResponseEntity.badRequest().build();

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT avatar_mime, avatar_data FROM profiles WHERE user_id = ?", userId);

            if (rows.isEmpty()) return ResponseEntity.notFound().build();
            Map<String, Object> row = rows.get(0);

            String mime = (String) row.get("avatar_mime");
            byte[] data = (byte[]) row.get("avatar_data");

            if (mime == null || mime.isBlank() || data == null || data.length == 0)
                return ResponseEntity.notFound().build();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mime));
            headers.setCacheControl("public, max-age=86400");
            return new ResponseEntity<>(data, headers, HttpStatus.OK);

        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    @DeleteMapping("/api/profile/avatar")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> delete(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        try {
            jdbc.update("UPDATE profiles SET avatar_mime = '', avatar_data = NULL WHERE user_id = ?", uid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Delete failed."));
        }
    }
}
