package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * POST /api/posts/views        batch-record impressions for a list of post IDs
 *      body: { postIds: ["id1","id2",...] }  — max 50
 *
 * GET  /api/posts/{id}/views   get impression count for a post (owner only)
 *
 * GET  /api/posts/my-stats     view counts for all my posts (last 30 days)
 */
@RestController
@RequestMapping("/api/posts")
public class PostViewController {

    private final JdbcTemplate jdbc;

    public PostViewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Batch record views ────────────────────────────────────────────────────
    @PostMapping("/views")
    public ResponseEntity<Map<String, Object>> recordViews(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Object raw = body.get("postIds");
        if (!(raw instanceof List<?> list)) return ResponseEntity.ok(Map.of("recorded", 0));

        int recorded = 0;
        for (Object item : list) {
            if (item == null) continue;
            String postId = item.toString().trim();
            if (postId.isEmpty()) continue;
            // Don't count views on own posts
            try {
                Integer isOwn = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM posts WHERE id = ? AND user_id = ?",
                    Integer.class, postId, uid);
                if (isOwn != null && isOwn > 0) continue;
            } catch (Exception ignored) { continue; }

            try {
                jdbc.update(
                    "INSERT OR IGNORE INTO post_views (id, post_id, viewer_id) VALUES (?,?,?)",
                    UUID.randomUUID().toString(), postId, uid);
                recorded++;
            } catch (Exception ignored) {}

            if (recorded >= 50) break; // cap per request
        }

        return ResponseEntity.ok(Map.of("recorded", recorded));
    }

    // ── Get view count for a single post ─────────────────────────────────────
    @GetMapping("/{id}/views")
    public ResponseEntity<Map<String, Object>> getViewCount(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        // Only the post owner can see exact counts
        Integer isOwn = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id = ? AND user_id = ?",
            Integer.class, id, uid);

        Long views = jdbc.queryForObject(
            "SELECT COUNT(*) FROM post_views WHERE post_id = ?", Long.class, id);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("postId",  id);
        resp.put("views",   views == null ? 0 : views);
        resp.put("isOwner", isOwn != null && isOwn > 0);
        return ResponseEntity.ok(resp);
    }

    // ── Per-post detailed analytics (owner only) ─────────────────────────────
    @GetMapping("/{id}/analytics")
    public ResponseEntity<Map<String, Object>> getPostAnalytics(
            @PathVariable String id, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Integer isOwn = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id = ? AND user_id = ?",
            Integer.class, id, uid);
        if (isOwn == null || isOwn == 0)
            return ResponseEntity.status(403).body(Map.of("error", "Not your post."));

        // Total views
        Long totalViews = jdbc.queryForObject(
            "SELECT COUNT(*) FROM post_views WHERE post_id = ?", Long.class, id);

        // Reaction breakdown
        List<Map<String, Object>> reactionRows = jdbc.queryForList(
            "SELECT reaction, COUNT(*) AS cnt FROM post_likes WHERE post_id = ? GROUP BY reaction", id);
        Map<String, Object> reactions = new LinkedHashMap<>();
        long totalReactions = 0;
        for (Map<String, Object> r : reactionRows) {
            long cnt = toLong(r.get("cnt"));
            reactions.put(s(r, "reaction"), cnt);
            totalReactions += cnt;
        }

        // Comments
        Long comments = jdbc.queryForObject(
            "SELECT COUNT(*) FROM post_comments WHERE post_id = ?", Long.class, id);

        // Reposts
        Long reposts = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE shared_from_id = ?", Long.class, id);

        // Daily views — last 7 days
        List<Map<String, Object>> dailyRows = jdbc.queryForList("""
            SELECT date(viewed_at) AS day, COUNT(*) AS cnt
            FROM post_views
            WHERE post_id = ?
              AND viewed_at >= date('now', '-6 days')
            GROUP BY date(viewed_at)
            ORDER BY day
            """, id);
        List<Map<String, Object>> dailyViews = new ArrayList<>();
        for (Map<String, Object> r : dailyRows) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date",  s(r, "day"));
            d.put("count", toLong(r.get("cnt")));
            dailyViews.add(d);
        }

        // Post snippet
        List<Map<String, Object>> postRows = jdbc.queryForList(
            "SELECT content, created_at FROM posts WHERE id = ?", id);
        String content   = postRows.isEmpty() ? "" : s(postRows.get(0), "content");
        String createdAt = postRows.isEmpty() ? "" : s(postRows.get(0), "created_at");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("postId",         id);
        resp.put("content",        content);
        resp.put("createdAt",      createdAt);
        resp.put("views",          totalViews  == null ? 0 : totalViews);
        resp.put("totalReactions", totalReactions);
        resp.put("reactions",      reactions);
        resp.put("comments",       comments    == null ? 0 : comments);
        resp.put("reposts",        reposts     == null ? 0 : reposts);
        resp.put("dailyViews",     dailyViews);
        return ResponseEntity.ok(resp);
    }

    // ── My posts stats (owner dashboard) ─────────────────────────────────────
    @GetMapping("/my-stats")
    public ResponseEntity<List<Map<String, Object>>> myStats(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT p.id, p.content, p.created_at,
                   (SELECT COUNT(*) FROM post_views  v WHERE v.post_id = p.id) AS views,
                   (SELECT COUNT(*) FROM post_likes  l WHERE l.post_id = p.id) AS reactions,
                   (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id) AS comments
            FROM posts p
            WHERE p.user_id = ?
              AND p.shared_from_id = ''
            ORDER BY views DESC, p.created_at DESC
            LIMIT 20
            """, uid);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("postId",    s(r, "id"));
            stat.put("content",   s(r, "content"));
            stat.put("createdAt", s(r, "created_at"));
            stat.put("views",     toLong(r.get("views")));
            stat.put("reactions", toLong(r.get("reactions")));
            stat.put("comments",  toLong(r.get("comments")));
            result.add(stat);
        }
        return ResponseEntity.ok(result);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l)    return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
}
