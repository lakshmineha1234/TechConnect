package com.techconnect.controller;

import com.techconnect.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

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
            @RequestBody Map<String, Object> body, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        String content = (body.getOrDefault("content", "") + "").trim();
        if (content.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Post content cannot be empty."));
        if (content.length() > 1000)
            return ResponseEntity.badRequest().body(Map.of("error", "Post too long (max 1000 characters)."));

        // Validate poll options if provided
        Object rawOpts = body.get("pollOptions");
        List<String> pollOpts = null;
        if (rawOpts instanceof List<?> optList && !optList.isEmpty()) {
            if (optList.size() < 2 || optList.size() > 4)
                return ResponseEntity.badRequest().body(Map.of("error", "Poll needs 2–4 options."));
            pollOpts = new ArrayList<>();
            for (Object o : optList) {
                String text = o.toString().trim();
                if (text.isEmpty())
                    return ResponseEntity.badRequest().body(Map.of("error", "Option text cannot be empty."));
                if (text.length() > 100)
                    return ResponseEntity.badRequest().body(Map.of("error", "Option too long (max 100 chars)."));
                pollOpts.add(text);
            }
        }

        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO posts (id, user_id, content) VALUES (?,?,?)", id, uid, content);

        // Extract and store hashtags
        extractHashtags(content).forEach(tag ->
            jdbc.update("INSERT OR IGNORE INTO post_hashtags (id, post_id, hashtag) VALUES (?,?,?)",
                UUID.randomUUID().toString(), id, tag));

        // Notify mentioned users
        notifyMentions(content, uid, id);

        // Insert poll options if present
        if (pollOpts != null) {
            for (int i = 0; i < pollOpts.size(); i++) {
                jdbc.update("INSERT INTO poll_options (id, post_id, option_text, position) VALUES (?,?,?,?)",
                    UUID.randomUUID().toString(), id, pollOpts.get(i), i);
            }
        }

        // Return the new post so the frontend can prepend it immediately
        Map<String, Object> post = buildPost(id, uid, content, Instant.now().toString(), 0, true);
        post.put("hasPoll", pollOpts != null);
        return ResponseEntity.ok(post);
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
                SELECT p.id, p.user_id, p.content, p.created_at, p.image_url, p.shared_from_id,
                       u.first_name, u.last_name, u.role,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)                    AS like_count,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?)  AS liked_by_me,
                       (SELECT reaction   FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?) AS my_reaction,
                       (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id)                    AS comment_count,
                       (SELECT COUNT(*) FROM post_saves    s WHERE s.post_id = p.id AND s.user_id = ?)  AS saved_by_me,
                       (SELECT COUNT(*) FROM poll_options  o WHERE o.post_id = p.id)                    AS has_poll,
                       (SELECT COUNT(*) FROM post_views   v WHERE v.post_id = p.id)                    AS view_count,
                       op.content    AS orig_content,
                       op.image_url  AS orig_image_url,
                       op.created_at AS orig_created_at,
                       op.user_id    AS orig_user_id,
                       ou.first_name AS orig_first_name,
                       ou.last_name  AS orig_last_name
                FROM posts p
                JOIN users u ON u.id = p.user_id
                LEFT JOIN posts op ON op.id = p.shared_from_id AND p.shared_from_id != ''
                LEFT JOIN users ou ON ou.id = op.user_id
                WHERE p.user_id = ?
                   OR p.user_id IN (
                       SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END
                       FROM connections WHERE status = 'accepted'
                         AND (requester_id = ? OR recipient_id = ?)
                   )
                ORDER BY p.created_at DESC
                LIMIT 20 OFFSET ?
                """, uid, uid, uid, uid, uid, uid, uid, offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String postId   = (String) r.get("id");
            String postUid  = (String) r.get("user_id");
            String content  = (String) r.get("content");
            String created  = (String) r.get("created_at");
            long   likes    = toLong(r.get("like_count"));
            boolean likedMe   = toLong(r.get("liked_by_me")) > 0;
            String  myReaction = s(r, "my_reaction");
            long commentCount = toLong(r.get("comment_count"));
            boolean savedByMe = toLong(r.get("saved_by_me")) > 0;
            Map<String, Object> post = buildPost(postId, postUid, content, created, likes, likedMe, commentCount);
            post.put("firstName",  s(r, "first_name"));
            post.put("lastName",   s(r, "last_name"));
            post.put("name",       (s(r, "first_name") + " " + s(r, "last_name")).trim());
            post.put("role",       s(r, "role"));
            post.put("isMine",       uid.equals(postUid));
            post.put("imageUrl",     s(r, "image_url"));
            post.put("savedByMe",    savedByMe);
            post.put("myReaction",   myReaction);
            post.put("viewCount",    toLong(r.get("view_count")));
            post.put("hasPoll",      toLong(r.get("has_poll")) > 0);
            post.put("sharedFromId", s(r, "shared_from_id"));
            // Original post data (only populated for reposts)
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

    // ── Network feed (connections only) ──────────────────────────────────────
    @GetMapping("/network")
    public ResponseEntity<List<Map<String, Object>>> getNetworkFeed(
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.user_id, p.content, p.created_at, p.image_url, p.shared_from_id,
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
                WHERE p.user_id IN (
                    SELECT CASE WHEN requester_id = ? THEN recipient_id ELSE requester_id END
                    FROM connections WHERE status = 'accepted'
                      AND (requester_id = ? OR recipient_id = ?)
                )
                ORDER BY p.created_at DESC
                LIMIT 20 OFFSET ?
                """, uid, uid, uid, uid, uid, uid, offset);

        return ResponseEntity.ok(mapFeedRows(rows, uid));
    }

    // ── Top posts (most engaged, last 7 days) ─────────────────────────────────
    @GetMapping("/top")
    public ResponseEntity<List<Map<String, Object>>> getTopFeed(
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.user_id, p.content, p.created_at, p.image_url, p.shared_from_id,
                       u.first_name, u.last_name, u.role,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)                   AS like_count,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?) AS liked_by_me,
                       (SELECT reaction   FROM post_likes  l WHERE l.post_id = p.id AND l.user_id = ?) AS my_reaction,
                       (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id)                   AS comment_count,
                       (SELECT COUNT(*) FROM post_saves    s WHERE s.post_id = p.id AND s.user_id = ?) AS saved_by_me,
                       (SELECT COUNT(*) FROM poll_options  o WHERE o.post_id = p.id)                   AS has_poll,
                       (SELECT COUNT(*) FROM post_views    v WHERE v.post_id = p.id)                   AS view_count,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)
                         + (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id) * 2
                         AS engagement_score
                FROM posts p
                JOIN users u ON u.id = p.user_id
                WHERE p.shared_from_id = ''
                  AND p.created_at >= datetime('now', '-7 days')
                ORDER BY engagement_score DESC, p.created_at DESC
                LIMIT 20 OFFSET ?
                """, uid, uid, uid, offset);

        return ResponseEntity.ok(mapFeedRows(rows, uid));
    }

    private List<Map<String, Object>> mapFeedRows(List<Map<String, Object>> rows, String uid) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String postId  = (String) r.get("id");
            String postUid = (String) r.get("user_id");
            Map<String, Object> post = buildPost(postId, postUid, s(r,"content"), s(r,"created_at"),
                    toLong(r.get("like_count")), toLong(r.get("liked_by_me")) > 0, toLong(r.get("comment_count")));
            post.put("firstName",    s(r, "first_name"));
            post.put("lastName",     s(r, "last_name"));
            post.put("name",         (s(r,"first_name") + " " + s(r,"last_name")).trim());
            post.put("role",         s(r, "role"));
            post.put("isMine",       uid.equals(postUid));
            post.put("imageUrl",     s(r, "image_url"));
            post.put("savedByMe",    toLong(r.get("saved_by_me")) > 0);
            post.put("myReaction",   s(r, "my_reaction"));
            post.put("viewCount",    toLong(r.get("view_count")));
            post.put("hasPoll",      toLong(r.get("has_poll")) > 0);
            post.put("sharedFromId", s(r, "shared_from_id"));
            result.add(post);
        }
        return result;
    }

    // ── Repost ────────────────────────────────────────────────────────────────
    @PostMapping("/{id}/repost")
    public ResponseEntity<Map<String, Object>> repost(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        // Verify original post exists and is not itself a repost
        List<Map<String, Object>> orig = jdbc.queryForList(
            "SELECT id, shared_from_id FROM posts WHERE id = ?", id);
        if (orig.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Post not found."));
        if (!s(orig.get(0), "shared_from_id").isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot repost a repost."));

        String comment = body != null ? (body.getOrDefault("content", "")).trim() : "";
        if (comment.length() > 500)
            return ResponseEntity.badRequest().body(Map.of("error", "Comment too long (max 500 chars)."));

        String newId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO posts (id, user_id, content, shared_from_id) VALUES (?,?,?,?)",
            newId, uid, comment, id);

        return ResponseEntity.ok(Map.of("ok", true, "postId", newId));
    }

    // ── Toggle reaction (like / love / insightful / celebrate / support) ──────
    @PostMapping("/{id}/like")
    public ResponseEntity<Map<String, Object>> toggleLike(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        // Validate reaction type
        String reaction = "like";
        if (body != null && body.get("reaction") instanceof String rt) {
            reaction = rt.trim().toLowerCase();
        }
        Set<String> validReactions = Set.of("like","love","insightful","celebrate","support");
        if (!validReactions.contains(reaction)) reaction = "like";

        // Check post exists
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Post not found."));

        // Check if user already reacted
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id, reaction FROM post_likes WHERE post_id = ? AND user_id = ?", id, uid);

        boolean nowReacted;
        String  nowReaction = "";
        if (!existing.isEmpty()) {
            String currentReaction = s(existing.get(0), "reaction");
            if (currentReaction.equals(reaction)) {
                // Same reaction — remove it (toggle off)
                jdbc.update("DELETE FROM post_likes WHERE post_id = ? AND user_id = ?", id, uid);
                nowReacted  = false;
            } else {
                // Different reaction — update it
                jdbc.update("UPDATE post_likes SET reaction = ? WHERE post_id = ? AND user_id = ?",
                    reaction, id, uid);
                nowReacted  = true;
                nowReaction = reaction;
            }
        } else {
            jdbc.update("INSERT INTO post_likes (id, post_id, user_id, reaction) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), id, uid, reaction);
            nowReacted  = true;
            nowReaction = reaction;
            // Notify post owner
            try {
                String postOwnerId = jdbc.queryForObject(
                    "SELECT user_id FROM posts WHERE id = ?", String.class, id);
                notifSvc.create(postOwnerId, "post_like", uid, id);
            } catch (Exception ignored) {}
        }

        long likeCount = toLong(jdbc.queryForObject(
            "SELECT COUNT(*) FROM post_likes WHERE post_id = ?", Long.class, id));

        // Aggregate reaction counts
        List<Map<String, Object>> reactionRows = jdbc.queryForList(
            "SELECT reaction, COUNT(*) AS cnt FROM post_likes WHERE post_id = ? GROUP BY reaction ORDER BY cnt DESC",
            id);
        Map<String, Long> reactionCounts = new LinkedHashMap<>();
        for (Map<String, Object> r : reactionRows)
            reactionCounts.put(s(r, "reaction"), toLong(r.get("cnt")));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("liked",          nowReacted);
        resp.put("likeCount",      likeCount);
        resp.put("myReaction",     nowReaction);
        resp.put("reactionCounts", reactionCounts);
        return ResponseEntity.ok(resp);
    }

    // ── Who reacted ───────────────────────────────────────────────────────────
    @GetMapping("/{id}/reactions")
    public ResponseEntity<List<Map<String, Object>>> reactions(
            @PathVariable String id, HttpSession session) {

        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.first_name, u.last_name, u.role, l.reaction
                FROM post_likes l
                JOIN users u ON u.id = l.user_id
                WHERE l.post_id = ?
                ORDER BY l.created_at DESC
                LIMIT 50
                """, id);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       r.get("id"));
            m.put("name",     (s(r, "first_name") + " " + s(r, "last_name")).trim());
            m.put("role",     r.get("role"));
            m.put("reaction", s(r, "reaction"));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── Edit own post ─────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> editPost(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String content = (body.getOrDefault("content", "")).trim();
        if (content.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Content cannot be empty."));
        if (content.length() > 1000)
            return ResponseEntity.badRequest().body(Map.of("error", "Post too long (max 1000 chars)."));

        int updated = jdbc.update(
            "UPDATE posts SET content = ? WHERE id = ? AND user_id = ? AND shared_from_id = ''",
            content, id, uid);
        if (updated == 0)
            return ResponseEntity.status(404).body(Map.of("error", "Post not found, not yours, or is a repost."));

        // Re-sync hashtags: delete old, insert new
        jdbc.update("DELETE FROM post_hashtags WHERE post_id = ?", id);
        extractHashtags(content).forEach(tag ->
            jdbc.update("INSERT OR IGNORE INTO post_hashtags (id, post_id, hashtag) VALUES (?,?,?)",
                UUID.randomUUID().toString(), id, tag));

        return ResponseEntity.ok(Map.of("id", id, "content", content));
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
                SELECT c.id, c.post_id, c.user_id, c.content, c.created_at, c.parent_id,
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
            c.put("parentId",  r.get("parent_id")); // null for top-level
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

        String parentId = body.getOrDefault("parentId", "").trim();
        if (parentId.isEmpty()) parentId = null;
        // Validate parent belongs to this post
        if (parentId != null) {
            Integer parentExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM post_comments WHERE id = ? AND post_id = ?",
                Integer.class, parentId, id);
            if (parentExists == null || parentExists == 0) parentId = null;
        }

        String cid = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        if (parentId != null) {
            jdbc.update("INSERT INTO post_comments (id, post_id, user_id, content, parent_id) VALUES (?,?,?,?,?)",
                    cid, id, uid, content, parentId);
        } else {
            jdbc.update("INSERT INTO post_comments (id, post_id, user_id, content) VALUES (?,?,?,?)",
                    cid, id, uid, content);
        }

        // Notify post owner (not self-comment, not for replies — reply author gets separate notif)
        if (parentId == null) {
            try {
                String postOwnerId = jdbc.queryForObject("SELECT user_id FROM posts WHERE id = ?", String.class, id);
                if (!uid.equals(postOwnerId)) notifSvc.create(postOwnerId, "post_comment", uid, id);
            } catch (Exception ignored) {}
        } else {
            // Notify the parent comment author
            try {
                String parentAuthorId = jdbc.queryForObject(
                    "SELECT user_id FROM post_comments WHERE id = ?", String.class, parentId);
                if (!uid.equals(parentAuthorId)) notifSvc.create(parentAuthorId, "comment_reply", uid, id);
            } catch (Exception ignored) {}
        }

        // Notify mentioned users
        notifyMentions(content, uid, id);

        final String finalParentId = parentId;

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
        resp.put("parentId",     finalParentId);
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

    // ── Posts by hashtag ─────────────────────────────────────────────────────
    @GetMapping("/tag/{tag}")
    public ResponseEntity<List<Map<String, Object>>> byTag(
            @PathVariable String tag,
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String normTag = tag.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (normTag.isEmpty()) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.user_id, p.content, p.created_at, p.image_url, p.shared_from_id,
                       u.first_name, u.last_name, u.role,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id)                   AS like_count,
                       (SELECT COUNT(*) FROM post_likes    l WHERE l.post_id = p.id AND l.user_id = ?) AS liked_by_me,
                       (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id)                   AS comment_count,
                       (SELECT COUNT(*) FROM post_saves    s WHERE s.post_id = p.id AND s.user_id = ?) AS saved_by_me
                FROM posts p
                JOIN users u ON u.id = p.user_id
                JOIN post_hashtags h ON h.post_id = p.id AND h.hashtag = ?
                ORDER BY p.created_at DESC
                LIMIT 20 OFFSET ?
                """, uid, uid, normTag, offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String postId  = (String) r.get("id");
            String postUid = (String) r.get("user_id");
            String content = (String) r.get("content");
            String created = (String) r.get("created_at");
            Map<String, Object> post = buildPost(postId, postUid, content, created,
                toLong(r.get("like_count")), toLong(r.get("liked_by_me")) > 0, toLong(r.get("comment_count")));
            post.put("firstName",    s(r, "first_name"));
            post.put("lastName",     s(r, "last_name"));
            post.put("name",         (s(r, "first_name") + " " + s(r, "last_name")).trim());
            post.put("role",         s(r, "role"));
            post.put("isMine",       uid.equals(postUid));
            post.put("imageUrl",     s(r, "image_url"));
            post.put("savedByMe",    toLong(r.get("saved_by_me")) > 0);
            post.put("sharedFromId", s(r, "shared_from_id"));
            result.add(post);
        }
        return ResponseEntity.ok(result);
    }

    // ── Trending hashtags ─────────────────────────────────────────────────────
    @GetMapping("/trending-tags")
    public ResponseEntity<List<Map<String, Object>>> trendingTags(HttpSession session) {
        if (session.getAttribute("userId") == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT hashtag, COUNT(*) AS cnt
                FROM post_hashtags
                WHERE created_at >= datetime('now', '-7 days')
                GROUP BY hashtag
                ORDER BY cnt DESC
                LIMIT 12
                """);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            result.add(Map.of("tag", r.get("hashtag"), "count", toLong(r.get("cnt"))));
        }
        return ResponseEntity.ok(result);
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

    private static final Pattern HASHTAG_PAT   = Pattern.compile("#([a-zA-Z][a-zA-Z0-9_]{0,49})");
    // New: @[Display Name](userId)
    private static final Pattern MENTION_NEW   = Pattern.compile("@\\[([^\\]]{1,80})\\]\\(([^)]{1,40})\\)");
    // Old: @First Last (name-based, kept for backward compat)
    private static final Pattern MENTION_OLD   = Pattern.compile("@([A-Za-z][A-Za-z ]{1,48}[A-Za-z])");

    private void notifyMentions(String content, String actorId, String refId) {
        if (content == null || content.isBlank()) return;
        Set<String> seen = new HashSet<>();

        // New format: @[Name](userId) — use userId directly, no ambiguity
        Matcher mn = MENTION_NEW.matcher(content);
        while (mn.find()) {
            String userId = mn.group(2).trim();
            if (userId.isBlank() || seen.contains(userId) || userId.equals(actorId)) continue;
            seen.add(userId);
            try { notifSvc.create(userId, "mention", actorId, refId); } catch (Exception ignored) {}
        }

        // Old format: @FirstName LastName — name lookup fallback
        Matcher mo = MENTION_OLD.matcher(content);
        while (mo.find()) {
            String fullName  = mo.group(1).trim();
            if (fullName.isBlank()) continue;
            String[] parts   = fullName.split("\\s+", 2);
            String firstName = parts[0];
            String lastName  = parts.length > 1 ? parts[1] : "";
            try {
                List<String> ids = lastName.isBlank()
                    ? jdbc.queryForList(
                        "SELECT id FROM users WHERE first_name = ? AND id != ? LIMIT 3",
                        String.class, firstName, actorId)
                    : jdbc.queryForList(
                        "SELECT id FROM users WHERE first_name = ? AND last_name = ? AND id != ? LIMIT 3",
                        String.class, firstName, lastName, actorId);
                ids.stream().filter(id -> !seen.contains(id)).forEach(id -> {
                    seen.add(id);
                    try { notifSvc.create(id, "mention", actorId, refId); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }
    private static List<String> extractHashtags(String content) {
        if (content == null || content.isBlank()) return List.of();
        Set<String> tags = new LinkedHashSet<>();
        Matcher m = HASHTAG_PAT.matcher(content);
        while (m.find()) tags.add(m.group(1).toLowerCase());
        return new ArrayList<>(tags);
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
