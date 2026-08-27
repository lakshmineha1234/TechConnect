package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * POST /api/hashtags/follow/{tag}    toggle follow/unfollow a hashtag
 * GET  /api/hashtags/followed        list tags the current user follows
 * GET  /api/posts/following          feed of posts from followed hashtags
 */
@RestController
public class HashtagController {

    private final JdbcTemplate jdbc;

    public HashtagController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Toggle follow ─────────────────────────────────────────────────────────
    @PostMapping("/api/hashtags/follow/{tag}")
    public ResponseEntity<Map<String, Object>> toggleFollow(
            @PathVariable String tag, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String norm = tag.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (norm.isEmpty() || norm.length() > 50)
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid hashtag."));

        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM followed_hashtags WHERE user_id=? AND hashtag=?",
            Integer.class, uid, norm);

        boolean nowFollowing;
        if (exists != null && exists > 0) {
            jdbc.update("DELETE FROM followed_hashtags WHERE user_id=? AND hashtag=?", uid, norm);
            nowFollowing = false;
        } else {
            jdbc.update("INSERT OR IGNORE INTO followed_hashtags (user_id, hashtag) VALUES (?,?)", uid, norm);
            nowFollowing = true;
        }

        long followerCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM followed_hashtags WHERE hashtag=?", Long.class, norm);

        return ResponseEntity.ok(Map.of("following", nowFollowing, "tag", norm, "followers", followerCount));
    }

    // ── List followed tags ────────────────────────────────────────────────────
    @GetMapping("/api/hashtags/followed")
    public ResponseEntity<List<String>> followed(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<String> tags = jdbc.queryForList(
            "SELECT hashtag FROM followed_hashtags WHERE user_id=? ORDER BY created_at DESC",
            String.class, uid);
        return ResponseEntity.ok(tags);
    }

    // ── Feed from followed hashtags ───────────────────────────────────────────
    @GetMapping("/api/posts/following")
    public ResponseEntity<List<Map<String, Object>>> followingFeed(
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT DISTINCT p.id, p.user_id, p.content, p.created_at, p.image_url,
                   u.first_name, u.last_name, u.role,
                   (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)                   AS like_count,
                   (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?) AS liked_by_me,
                   (SELECT reaction   FROM post_likes  l WHERE l.post_id = p.id AND l.user_id = ?) AS my_reaction,
                   (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id)                   AS comment_count,
                   (SELECT COUNT(*) FROM post_saves    s WHERE s.post_id = p.id AND s.user_id = ?) AS saved_by_me,
                   (SELECT COUNT(*) FROM poll_options  o WHERE o.post_id = p.id)                   AS has_poll,
                   (SELECT COUNT(*) FROM post_views    v WHERE v.post_id = p.id)                   AS view_count
            FROM posts p
            JOIN users u ON u.id = p.user_id
            JOIN post_hashtags h ON h.post_id = p.id
            JOIN followed_hashtags fh ON fh.hashtag = h.hashtag AND fh.user_id = ?
            WHERE (p.scheduled_at IS NULL OR p.scheduled_at <= datetime('now'))
            ORDER BY p.created_at DESC
            LIMIT 20 OFFSET ?
            """, uid, uid, uid, uid, offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String postId  = (String) r.get("id");
            String postUid = (String) r.get("user_id");
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("id",           postId);
            post.put("userId",       postUid);
            post.put("content",      s(r, "content"));
            post.put("createdAt",    s(r, "created_at"));
            post.put("likeCount",    toLong(r.get("like_count")));
            post.put("likedByMe",    toLong(r.get("liked_by_me")) > 0);
            post.put("commentCount", toLong(r.get("comment_count")));
            post.put("firstName",    s(r, "first_name"));
            post.put("lastName",     s(r, "last_name"));
            post.put("name",         (s(r, "first_name") + " " + s(r, "last_name")).trim());
            post.put("role",         s(r, "role"));
            post.put("isMine",       uid.equals(postUid));
            post.put("imageUrl",     s(r, "image_url"));
            post.put("savedByMe",    toLong(r.get("saved_by_me")) > 0);
            post.put("myReaction",   s(r, "my_reaction"));
            post.put("viewCount",    toLong(r.get("view_count")));
            post.put("hasPoll",      toLong(r.get("has_poll")) > 0);
            post.put("sharedFromId", "");
            result.add(post);
        }
        return ResponseEntity.ok(result);
    }

    private static String s(Map<?, ?> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
}
