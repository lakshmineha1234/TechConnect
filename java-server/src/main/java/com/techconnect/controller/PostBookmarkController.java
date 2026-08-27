package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * POST /api/posts/{id}/bookmark   toggle bookmark (save/unsave)
 * GET  /api/posts/saved           list all saved posts for the current user
 */
@RestController
@RequestMapping("/api/posts")
public class PostBookmarkController {

    private final JdbcTemplate jdbc;

    public PostBookmarkController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Toggle bookmark ───────────────────────────────────────────────────────
    @PostMapping("/{id}/bookmark")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) return err(404, "Post not found.");

        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM post_saves WHERE post_id = ? AND user_id = ?",
            Integer.class, id, uid);

        boolean nowSaved;
        if (already != null && already > 0) {
            jdbc.update("DELETE FROM post_saves WHERE post_id = ? AND user_id = ?", id, uid);
            nowSaved = false;
        } else {
            jdbc.update("INSERT INTO post_saves (id, post_id, user_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString(), id, uid);
            nowSaved = true;
        }

        return ResponseEntity.ok(Map.of("saved", nowSaved));
    }

    // ── List saved posts ──────────────────────────────────────────────────────
    @GetMapping("/saved")
    public ResponseEntity<List<Map<String, Object>>> saved(HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT p.id, p.user_id, p.content, p.created_at, p.image_url, p.shared_from_id,
                   u.first_name, u.last_name, u.role,
                   (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)                   AS like_count,
                   (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?) AS liked_by_me,
                   (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id)                   AS comment_count,
                   op.content    AS orig_content,
                   op.image_url  AS orig_image_url,
                   op.created_at AS orig_created_at,
                   op.user_id    AS orig_user_id,
                   ou.first_name AS orig_first_name,
                   ou.last_name  AS orig_last_name
            FROM post_saves sv
            JOIN posts p ON p.id = sv.post_id
            JOIN users u ON u.id = p.user_id
            LEFT JOIN posts op ON op.id = p.shared_from_id AND p.shared_from_id != ''
            LEFT JOIN users ou ON ou.id = op.user_id
            WHERE sv.user_id = ?
            ORDER BY sv.created_at DESC
            LIMIT 50
            """, uid, uid);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("id",           r.get("id"));
            post.put("userId",       s(r, "user_id"));
            post.put("content",      s(r, "content"));
            post.put("createdAt",    s(r, "created_at"));
            post.put("imageUrl",     s(r, "image_url"));
            post.put("likeCount",    toLong(r.get("like_count")));
            post.put("likedByMe",    toLong(r.get("liked_by_me")) > 0);
            post.put("commentCount", toLong(r.get("comment_count")));
            post.put("firstName",    s(r, "first_name"));
            post.put("lastName",     s(r, "last_name"));
            post.put("name",         (s(r, "first_name") + " " + s(r, "last_name")).trim());
            post.put("role",         s(r, "role"));
            post.put("isMine",       uid.equals(s(r, "user_id")));
            post.put("savedByMe",    true);
            post.put("sharedFromId", s(r, "shared_from_id"));
            if (!s(r, "shared_from_id").isEmpty()) {
                post.put("origContent",    s(r, "orig_content"));
                post.put("origImageUrl",   s(r, "orig_image_url"));
                post.put("origCreatedAt",  s(r, "orig_created_at"));
                post.put("origUserId",     s(r, "orig_user_id"));
                String ofn = s(r, "orig_first_name");
                String oln = s(r, "orig_last_name");
                post.put("origAuthorName", (ofn + " " + oln).trim());
            }
            result.add(post);
        }
        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l)    return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
