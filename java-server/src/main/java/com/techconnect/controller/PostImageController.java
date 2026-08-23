package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/posts/{id}/image   upload image for a post (owner only, max 8 MB)
 * GET  /api/posts/{id}/image   serve the image (any authenticated user)
 * DELETE /api/posts/{id}/image remove the image from a post (owner only)
 */
@RestController
public class PostImageController {

    private static final Set<String> ALLOWED_MIME = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final long MAX_BYTES = 8L * 1024 * 1024; // 8 MB

    private final JdbcTemplate jdbc;
    private final Path imageDir;

    public PostImageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        Path here   = Paths.get("").toAbsolutePath();
        Path parent = here.getParent() != null ? here.getParent() : here;
        this.imageDir = parent.resolve("uploads").resolve("post-images");
        try { Files.createDirectories(this.imageDir); } catch (Exception ignored) {}
    }

    // ── Upload ────────────────────────────────────────────────────────────────
    @PostMapping("/api/posts/{id}/image")
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        // Must own the post
        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id = ? AND user_id = ?", Integer.class, id, uid);
        if (owns == null || owns == 0) return err(403, "Post not found or not yours.");

        if (file == null || file.isEmpty()) return err(400, "No file provided.");

        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME.contains(mime.toLowerCase()))
            return err(400, "Only JPEG, PNG, GIF, or WebP images are allowed.");

        if (file.getSize() > MAX_BYTES) return err(400, "Image must be under 8 MB.");

        String ext = mimeToExt(mime);
        try {
            // Remove any previous image file for this post (different extension)
            for (String e : List.of(".jpg", ".png", ".gif", ".webp")) {
                Files.deleteIfExists(imageDir.resolve(id + e));
            }

            Path dest = imageDir.resolve(id + ext);
            Files.write(dest, file.getBytes());

            String url = "/api/posts/" + id + "/image";
            jdbc.update("UPDATE posts SET image_url = ? WHERE id = ?", url, id);

            return ResponseEntity.ok(Map.of("ok", true, "imageUrl", url));
        } catch (Exception e) {
            return err(500, "Upload failed: " + e.getMessage());
        }
    }

    // ── Serve ─────────────────────────────────────────────────────────────────
    @GetMapping("/api/posts/{id}/image")
    public ResponseEntity<byte[]> serve(
            @PathVariable String id,
            HttpSession session) {

        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        if (!id.matches("[a-zA-Z0-9\\-]{1,64}"))
            return ResponseEntity.badRequest().build();

        for (Map.Entry<String, String> e : Map.of(
                ".jpg", "image/jpeg", ".png", "image/png",
                ".gif", "image/gif",  ".webp", "image/webp").entrySet()) {
            Path f = imageDir.resolve(id + e.getKey());
            if (Files.exists(f)) {
                try {
                    byte[] bytes = Files.readAllBytes(f);
                    HttpHeaders h = new HttpHeaders();
                    h.set(HttpHeaders.CONTENT_TYPE, e.getValue());
                    h.setCacheControl("public, max-age=86400");
                    return new ResponseEntity<>(bytes, h, HttpStatus.OK);
                } catch (Exception ex) {
                    return ResponseEntity.status(500).build();
                }
            }
        }
        return ResponseEntity.notFound().build();
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    @DeleteMapping("/api/posts/{id}/image")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id = ? AND user_id = ?", Integer.class, id, uid);
        if (owns == null || owns == 0) return err(403, "Post not found or not yours.");

        for (String ext : List.of(".jpg", ".png", ".gif", ".webp")) {
            try { Files.deleteIfExists(imageDir.resolve(id + ext)); } catch (Exception ignored) {}
        }
        jdbc.update("UPDATE posts SET image_url = '' WHERE id = ?", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String mimeToExt(String mime) {
        return switch (mime.toLowerCase()) {
            case "image/png"  -> ".png";
            case "image/gif"  -> ".gif";
            case "image/webp" -> ".webp";
            default           -> ".jpg";
        };
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
