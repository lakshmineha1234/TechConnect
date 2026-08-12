package com.techconnect.controller;

import com.techconnect.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Activity Feed — posts created by the user and their connections.
 *
 * POST   /api/posts                       create a post
 * GET    /api/posts/feed                  feed: own posts + connections' posts
 * POST   /api/posts/{id}/like             toggle like on a post
 * DELETE /api/posts/{id}                  delete own post
 * GET    /api/posts/activity              count of feed posts in last 7 days (dashboard badge)
 *
 * GET    /api/posts/{id}/comments         list comments on a post
 * POST   /api/posts/{id}/comments         add a comment
 * DELETE /api/posts/{id}/comments/{cid}   delete own comment
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final JdbcTemplate jdbc;
    private final NotificationService notifSvc;

    public PostController(JdbcTemplate jdbc, NotificationService notifSvc) {
        this.jdbc = jdbc;
        this.notifSvc = notifSvc;
    }

    // ── Create a post ─────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPost(
            @RequestBody Map<String, String> body, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        String content = (body.getOrDefault("content", "")).trim();
        if (content.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Post content cannot be empty."));
        if (content.length() > 1000)
            return ResponseEntity.badRequest().body(Map.of("error", "Post too long (max 1000 characters)."));

        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO posts (id, user_id, content) VALUES (?,?,?)", id, uid, content);

        // Return the new post so the frontend can prepend it immediately
        return ResponseEntity.ok(buildPost(id, uid, content, Instant.now().toString(), 0, true));
    }

    // ── Feed ──────────────────────────────────────────────────────────────────
    @GetMapping("/feed")
    public ResponseEntity<List<Map<String, Object>>> getFeed(
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        // Posts by self + accepted connections, newest first, paginated 20 at a time
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.user_id, p.content, p.created_at,
                       u.first_name, u.last_name, u.role,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)                    AS like_count,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?)  AS liked_by_me,
                       (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id)                    AS comment_count
                FROM posts p
                JOIN users u ON u.id = p.user_id
                WHERE p.user_id = ?
                   OR p.user_id IN (
                       SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END
                       FROM connections WHERE status = 'accepted'
                         AND (requester_id = ? OR recipient_id = ?)
                   )
                ORDER BY p.created_at DESC
                LIMIT 20 OFFSET ?
                """, uid, uid, uid, uid, uid, offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String postId   = (String) r.get("id");
            String postUid  = (String) r.get("user_id");
            String content  = (String) r.get("content");
            String created  = (String) r.get("created_at");
            long   likes    = toLong(r.get("like_count"));
            boolean likedMe = toLong(r.get("liked_by_me")) > 0;
            long commentCount = toLong(r.get("comment_count"));
            Map<String, Object> post = buildPost(postId, postUid, content, created, likes, likedMe, commentCount);
            post.put("firstName", s(r, "first_name"));
            post.put("lastName",  s(r, "last_name"));
            post.put("name",      (s(r, "first_name") + " " + s(r, "last_name")).trim());
            post.put("role",      s(r, "role"));
            post.put("isMine",    uid.equals(postUid));
            result.add(post);
        }
        return ResponseEntity.ok(result);
    }

    // ── Toggle like ───────────────────────────────────────────────────────────
    @PostMapping("/{id}/like")
    public ResponseEntity<Map<String, Object>> toggleLike(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        // Check post exists
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Post not found."));

        // Toggle: remove if already liked, add if not
        Integer alreadyLiked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM post_likes WHERE post_id = ? AND user_id = ?",
                Integer.class, id, uid);

        boolean nowLiked;
        if (alreadyLiked != null && alreadyLiked > 0) {
            jdbc.update("DELETE FROM post_likes WHERE post_id = ? AND user_id = ?", id, uid);
            nowLiked = false;
        } else {
            jdbc.update("INSERT OR IGNORE INTO post_likes (id, post_id, user_id) VALUES (?,?,?)",
                    UUID.randomUUID().toString(), id, uid);
            nowLiked = true;
            // Notify the post owner (not if they liked their own post)
            try {
                String postOwnerId = jdbc.queryForObject(
                        "SELECT user_id FROM posts WHERE id = ?", String.class, id);
                notifSvc.create(postOwnerId, "post_like", uid, id);
            } catch (Exception ignored) {}
        }

        long likeCount = toLong(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Long.class, id));

        return ResponseEntity.ok(Map.of("liked", nowLiked, "likeCount", likeCount));
    }

    // ── Delete own post ───────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deletePost(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        int deleted = jdbc.update("DELETE FROM posts WHERE id = ? AND user_id = ?", id, uid);
        if (deleted == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Post not found or not yours."));

        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── List comments ────────────────────────────────────────────────────────
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<Map<String, Object>>> listComments(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.post_id, c.user_id, c.content, c.created_at,
                       u.first_name, u.last_name
                FROM post_comments c
                JOIN users u ON u.id = c.user_id
                WHERE c.post_id = ?
                ORDER BY c.created_at ASC
                """, id);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id",        r.get("id"));
            c.put("postId",    s(r, "post_id"));
            c.put("userId",    s(r, "user_id"));
            c.put("content",   s(r, "content"));
            c.put("createdAt", s(r, "created_at"));
            c.put("name",      (s(r, "first_name") + " " + s(r, "last_name")).trim());
            c.put("isMine",    uid.equals(s(r, "user_id")));
            result.add(c);
        }
        return ResponseEntity.ok(result);
    }

    // ── Add a comment ─────────────────────────────────────────────────────────
    @PostMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        String content = (body.getOrDefault("content", "")).trim();
        if (content.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Comment cannot be empty."));
        if (content.length() > 500)
            return ResponseEntity.badRequest().body(Map.of("error", "Comment too long (max 500 chars)."));

        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Post not found."));

        String cid = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO post_comments (id, post_id, user_id, content) VALUES (?,?,?,?)",
                cid, id, uid, content);

        // Notify post owner (not self-comment)
        try {
            String postOwnerId = jdbc.queryForObject("SELECT user_id FROM posts WHERE id = ?", String.class, id);
            notifSvc.create(postOwnerId, "post_comment", uid, id);
        } catch (Exception ignored) {}

        // Fetch commenter name
        String name = "";
        try {
            Map<String, Object> u = jdbc.queryForMap("SELECT first_name, last_name FROM users WHERE id = ?", uid);
            name = (u.get("first_name") + " " + u.get("last_name")).toString().trim();
        } catch (Exception ignored) {}

        // Return new comment + updated total count
        long total = toLong(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post_comments WHERE post_id = ?", Long.class, id));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",           cid);
        resp.put("postId",       id);
        resp.put("userId",       uid);
        resp.put("content",      content);
        resp.put("createdAt",    now);
        resp.put("name",         name);
        resp.put("isMine",       true);
        resp.put("commentCount", total);
        return ResponseEntity.ok(resp);
    }

    // ── Delete own comment ────────────────────────────────────────────────────
    @DeleteMapping("/{id}/comments/{cid}")
    public ResponseEntity<Map<String, Object>> deleteComment(
            @PathVariable String id,
            @PathVariable String cid,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        int deleted = jdbc.update(
                "DELETE FROM post_comments WHERE id = ? AND post_id = ? AND user_id = ?",
                cid, id, uid);
        if (deleted == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Comment not found or not yours."));

        long total = toLong(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post_comments WHERE post_id = ?", Long.class, id));
        return ResponseEntity.ok(Map.of("ok", true, "commentCount", total));
    }

    // ── Activity count (dashboard badge) ─────────────────────────────────────
    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> activityCount(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM posts
                WHERE created_at >= datetime('now', '-7 days')
                  AND (user_id = ?
                    OR user_id IN (
                        SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END
                        FROM connections WHERE status = 'accepted'
                          AND (requester_id = ? OR recipient_id = ?)
                    ))
                """, Long.class, uid, uid, uid, uid);

        return ResponseEntity.ok(Map.of("activity", count == null ? 0 : count));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private Map<String, Object> buildPost(String id, String userId, String content,
                                          String createdAt, long likeCount, boolean likedByMe,
                                          long commentCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           id);
        m.put("userId",       userId);
        m.put("content",      content);
        m.put("createdAt",    createdAt);
        m.put("likeCount",    likeCount);
        m.put("likedByMe",    likedByMe);
        m.put("commentCount", commentCount);
        return m;
    }

    // Overload for createPost (no comment count needed — brand new post)
    private Map<String, Object> buildPost(String id, String userId, String content,
                                          String createdAt, long likeCount, boolean likedByMe) {
        return buildPost(id, userId, content, createdAt, likeCount, likedByMe, 0);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
}
