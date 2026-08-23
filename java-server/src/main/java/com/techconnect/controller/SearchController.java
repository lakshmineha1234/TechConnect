package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * GET /api/search/users?q=...&role=...&location=...&skill=...&openToWork=...&offset=...
 *
 * Searches users by name, job title, institution, degree, bio, or skills.
 * Returns up to 20 results per page ordered by relevance score (name match
 * scores higher than skill/bio match), then by name alphabetically.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final JdbcTemplate jdbc;

    public SearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> searchUsers(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String role,
            @RequestParam(defaultValue = "") String location,
            @RequestParam(defaultValue = "") String skill,
            @RequestParam(defaultValue = "false") boolean openToWork,
            @RequestParam(defaultValue = "0") int offset,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String query    = q.trim();        if (query.length()    > 100) query    = query.substring(0, 100);
        String locClean = location.trim(); if (locClean.length() > 100) locClean = locClean.substring(0, 100);
        String skClean  = skill.trim();    if (skClean.length()  > 100) skClean  = skClean.substring(0, 100);

        String like    = "%" + query.toLowerCase() + "%";
        String locLike = "%" + locClean.toLowerCase() + "%";

        List<Object> params = new ArrayList<>();

        String sql = """
            SELECT DISTINCT
                   u.id, u.first_name, u.last_name, u.role, u.email,
                   p.job_title, p.company, p.institution, p.location, p.bio,
                   p.open_to_work, p.is_hiring,
                   CASE
                     WHEN lower(u.first_name || ' ' || u.last_name) LIKE ? THEN 3
                     WHEN lower(p.job_title) LIKE ? OR lower(p.institution) LIKE ? THEN 2
                     ELSE 1
                   END AS score,
                   (SELECT status FROM connections
                    WHERE (requester_id = ? AND recipient_id = u.id)
                       OR (requester_id = u.id AND recipient_id = ?)
                    LIMIT 1) AS conn_status,
                   (SELECT requester_id FROM connections
                    WHERE (requester_id = ? AND recipient_id = u.id)
                       OR (requester_id = u.id AND recipient_id = ?)
                    LIMIT 1) AS conn_requester,
                   CASE
                     WHEN EXISTS (
                       SELECT 1 FROM connections
                       WHERE status='accepted'
                         AND ((requester_id=? AND recipient_id=u.id)
                           OR (requester_id=u.id AND recipient_id=?))
                     ) THEN 1
                     WHEN EXISTS (
                       SELECT 1 FROM connections c1
                       JOIN connections c2 ON (
                         CASE WHEN c1.requester_id=? THEN c1.recipient_id ELSE c1.requester_id END
                         = CASE WHEN c2.requester_id=u.id THEN c2.recipient_id ELSE c2.requester_id END
                       )
                       WHERE c1.status='accepted' AND c2.status='accepted'
                         AND (c1.requester_id=? OR c1.recipient_id=?)
                         AND (c2.requester_id=u.id OR c2.recipient_id=u.id)
                     ) THEN 2
                     ELSE 3
                   END AS conn_degree
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            LEFT JOIN skills   s ON s.user_id = u.id
            WHERE u.id != ?
              AND (? = '' OR (
                lower(u.first_name || ' ' || u.last_name) LIKE ?
                OR lower(p.job_title)   LIKE ?
                OR lower(p.institution) LIKE ?
                OR lower(p.bio)         LIKE ?
                OR lower(p.company)     LIKE ?
                OR lower(s.skill_name)  LIKE ?
              ))
              AND (? = '' OR u.role = ?)
              AND (? = '' OR lower(p.location) LIKE ?)
              AND (? = '' OR EXISTS (
                    SELECT 1 FROM skills sk
                    WHERE sk.user_id = u.id AND lower(sk.skill_name) = lower(?)))
              AND (? = 0  OR p.open_to_work = 1)
            ORDER BY score DESC, u.first_name ASC, u.last_name ASC
            LIMIT 20 OFFSET ?
            """;

        params.add(like); params.add(like); params.add(like);  // CASE scoring
        params.add(uid); params.add(uid);                       // conn_status
        params.add(uid); params.add(uid);                       // conn_requester
        params.add(uid); params.add(uid);                       // degree: 1st EXISTS
        params.add(uid); params.add(uid); params.add(uid);      // degree: 2nd EXISTS
        params.add(uid);                                         // exclude self
        params.add(query); params.add(like); params.add(like); params.add(like); // text search
        params.add(like); params.add(like); params.add(like);   // bio/company/skill
        params.add(role); params.add(role.isEmpty() ? "" : role); // role filter
        params.add(locClean); params.add(locLike);              // location filter
        params.add(skClean); params.add(skClean);               // skill filter
        params.add(openToWork ? 1 : 0);                         // open to work
        params.add(offset);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());

        // Count total for pagination
        String countSql = """
            SELECT COUNT(DISTINCT u.id)
            FROM users u
            LEFT JOIN profiles p ON p.user_id = u.id
            LEFT JOIN skills   s ON s.user_id = u.id
            WHERE u.id != ?
              AND (? = '' OR (
                lower(u.first_name || ' ' || u.last_name) LIKE ?
                OR lower(p.job_title)   LIKE ?
                OR lower(p.institution) LIKE ?
                OR lower(p.bio)         LIKE ?
                OR lower(p.company)     LIKE ?
                OR lower(s.skill_name)  LIKE ?
              ))
              AND (? = '' OR u.role = ?)
              AND (? = '' OR lower(p.location) LIKE ?)
              AND (? = '' OR EXISTS (
                    SELECT 1 FROM skills sk
                    WHERE sk.user_id = u.id AND lower(sk.skill_name) = lower(?)))
              AND (? = 0  OR p.open_to_work = 1)
            """;
        Integer total = jdbc.queryForObject(countSql, Integer.class,
            uid, query, like, like, like, like, like, like,
            role, role.isEmpty() ? "" : role,
            locClean, locLike,
            skClean, skClean,
            openToWork ? 1 : 0);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id",          s(r, "id"));
            user.put("firstName",   s(r, "first_name"));
            user.put("lastName",    s(r, "last_name"));
            user.put("name",        (s(r, "first_name") + " " + s(r, "last_name")).trim());
            user.put("role",        s(r, "role"));
            user.put("jobTitle",    s(r, "job_title"));
            user.put("company",     s(r, "company"));
            user.put("institution", s(r, "institution"));
            user.put("location",    s(r, "location"));
            user.put("bio",         s(r, "bio"));
            user.put("openToWork",  toBool(r.get("open_to_work")));
            user.put("isHiring",    toBool(r.get("is_hiring")));
            // Connection state
            String connStatus    = s(r, "conn_status");
            String connRequester = s(r, "conn_requester");
            user.put("connStatus",    connStatus);
            user.put("connInitiatedByMe", uid.equals(connRequester));
            Object degVal = r.get("conn_degree");
            user.put("connDegree", degVal instanceof Number n ? n.intValue() : 3);
            results.add(user);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("results", results);
        resp.put("total",   total == null ? 0 : total);
        resp.put("offset",  offset);
        resp.put("hasMore", results.size() == 20);
        return ResponseEntity.ok(resp);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k); return v == null ? "" : v.toString();
    }
    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
    }
}
