package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * POST /api/posts/{id}/poll          create poll options for a post (owner only, once)
 *      body: { options: ["Option A", "Option B", ...] }  — 2 to 4 options
 *
 * GET  /api/posts/{id}/poll          get poll options + vote counts + my vote
 *
 * POST /api/posts/{id}/poll/vote     cast or change vote
 *      body: { optionId: "..." }
 */
@RestController
@RequestMapping("/api/posts/{postId}/poll")
public class PollController {

    private final JdbcTemplate jdbc;

    public PollController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Create poll options ───────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPoll(
            @PathVariable String postId,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM posts WHERE id = ? AND user_id = ?", Integer.class, postId, uid);
        if (owns == null || owns == 0) return err(403, "Not your post.");

        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM poll_options WHERE post_id = ?", Integer.class, postId);
        if (existing != null && existing > 0) return err(400, "Poll already exists on this post.");

        Object rawOptions = body.get("options");
        if (!(rawOptions instanceof List<?> optList)) return err(400, "options must be a list.");
        if (optList.size() < 2 || optList.size() > 4) return err(400, "Poll needs 2–4 options.");

        for (int i = 0; i < optList.size(); i++) {
            String text = optList.get(i).toString().trim();
            if (text.isEmpty()) return err(400, "Option text cannot be empty.");
            if (text.length() > 100) return err(400, "Option too long (max 100 chars).");
            jdbc.update("INSERT INTO poll_options (id, post_id, option_text, position) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), postId, text, i);
        }
        return ResponseEntity.ok(getPollData(postId, uid));
    }

    // ── Get poll data ─────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<Map<String, Object>> getPoll(
            @PathVariable String postId, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM poll_options WHERE post_id = ?", Integer.class, postId);
        if (exists == null || exists == 0) return ResponseEntity.ok(Map.of("hasPoll", false));

        return ResponseEntity.ok(getPollData(postId, uid));
    }

    // ── Cast / change vote ────────────────────────────────────────────────────
    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(
            @PathVariable String postId,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return err(401, "Not authenticated.");

        String optionId = body.get("optionId") == null ? "" : body.get("optionId").toString().trim();
        if (optionId.isEmpty()) return err(400, "optionId required.");

        // Verify option belongs to this post
        Integer valid = jdbc.queryForObject(
            "SELECT COUNT(*) FROM poll_options WHERE id = ? AND post_id = ?",
            Integer.class, optionId, postId);
        if (valid == null || valid == 0) return err(404, "Option not found.");

        // Remove existing vote then insert new one (allows changing vote)
        jdbc.update("DELETE FROM poll_votes WHERE post_id = ? AND user_id = ?", postId, uid);
        jdbc.update("INSERT INTO poll_votes (id, post_id, option_id, user_id) VALUES (?,?,?,?)",
            UUID.randomUUID().toString(), postId, optionId, uid);

        return ResponseEntity.ok(getPollData(postId, uid));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Map<String, Object> getPollData(String postId, String uid) {
        List<Map<String, Object>> opts = jdbc.queryForList(
            "SELECT id, option_text, position FROM poll_options WHERE post_id = ? ORDER BY position",
            postId);

        // Vote counts per option
        List<Map<String, Object>> counts = jdbc.queryForList(
            "SELECT option_id, COUNT(*) AS cnt FROM poll_votes WHERE post_id = ? GROUP BY option_id",
            postId);
        Map<String, Long> countMap = new HashMap<>();
        for (Map<String, Object> c : counts)
            countMap.put((String) c.get("option_id"), toLong(c.get("cnt")));

        long totalVotes = countMap.values().stream().mapToLong(Long::longValue).sum();

        // My vote
        String myVote = "";
        try {
            myVote = jdbc.queryForObject(
                "SELECT option_id FROM poll_votes WHERE post_id = ? AND user_id = ?",
                String.class, postId, uid);
        } catch (Exception ignored) {}
        final String myVoteFinal = myVote == null ? "" : myVote;

        List<Map<String, Object>> options = new ArrayList<>();
        for (Map<String, Object> o : opts) {
            String oid = (String) o.get("id");
            long   cnt = countMap.getOrDefault(oid, 0L);
            double pct = totalVotes > 0 ? (cnt * 100.0 / totalVotes) : 0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",         oid);
            item.put("text",       s(o, "option_text"));
            item.put("votes",      cnt);
            item.put("pct",        Math.round(pct));
            item.put("isMyVote",   oid.equals(myVoteFinal));
            options.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasPoll",    true);
        result.put("options",    options);
        result.put("totalVotes", totalVotes);
        result.put("myVote",     myVoteFinal);
        return result;
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Long l)    return l;
        if (v instanceof Integer i) return i.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }
    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
