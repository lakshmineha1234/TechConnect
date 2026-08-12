package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Profile-photo upload and serving.
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
    private final Path avatarDir;

    public AvatarController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // Store avatars in {projectRoot}/uploads/avatars/
        Path here   = Paths.get("").toAbsolutePath();
        Path parent = here.getParent() != null ? here.getParent() : here;
        this.avatarDir = parent.resolve("uploads").resolve("avatars");
        try { Files.createDirectories(this.avatarDir); } catch (Exception ignored) {}
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
            Path dest = avatarDir.resolve(uid);
            Files.write(dest, file.getBytes());
            // Upsert profile row and set avatar_mime
            jdbc.update("""
                    INSERT INTO profiles (user_id, avatar_mime) VALUES (?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET avatar_mime = excluded.avatar_mime
                    """, uid, mime.toLowerCase());
            return ResponseEntity.ok(Map.of("ok", true, "mime", mime.toLowerCase()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    // ── Serve ─────────────────────────────────────────────────────────────────
    @GetMapping("/api/profile/avatar/{userId}")
    public ResponseEntity<byte[]> serve(@PathVariable String userId) {
        // Sanitise path — reject anything that isn't a plain UUID
        if (!userId.matches("[a-zA-Z0-9\\-]{1,64}"))
            return ResponseEntity.badRequest().build();

        try {
            // Look up stored mime type
            String mime = null;
            try {
                mime = jdbc.queryForObject(
                        "SELECT avatar_mime FROM profiles WHERE user_id = ?",
                        String.class, userId);
            } catch (Exception ignored) {}

            if (mime == null || mime.isBlank())
                return ResponseEntity.notFound().build();

            Path file = avatarDir.resolve(userId);
            if (!Files.exists(file)) return ResponseEntity.notFound().build();

            byte[] bytes = Files.readAllBytes(file);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mime));
            headers.setCacheControl("public, max-age=86400");  // cache 1 day
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);

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
            Files.deleteIfExists(avatarDir.resolve(uid));
            jdbc.update("UPDATE profiles SET avatar_mime = '' WHERE user_id = ?", uid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Delete failed."));
        }
    }
}
