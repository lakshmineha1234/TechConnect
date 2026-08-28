package com.techconnect.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Social OAuth 2.0 sign-in for Google, LinkedIn, and GitHub.
 *
 * ── Google (client-side GSI) ───────────────────────────────────────────────
 *   POST /api/auth/google                    receive GSI JWT credential
 *   POST /api/auth/google  + role            create new account from GSI JWT
 *
 * ── LinkedIn / GitHub (server-side redirect) ──────────────────────────────
 *   GET  /api/auth/linkedin                  redirect to LinkedIn auth page
 *   GET  /api/auth/linkedin/callback         receive code, create session
 *   GET  /api/auth/github                    redirect to GitHub auth page
 *   GET  /api/auth/github/callback           receive code, create session
 *
 * ── Shared (used after any redirect-based social login) ───────────────────
 *   GET  /api/auth/social/pending            get stored profile for role picker
 *   POST /api/auth/social/complete  + role   create account, start session
 *
 * CREDENTIALS — fill in the constants below, then rebuild:
 *   Google  : console.cloud.google.com  → APIs & Services → Credentials
 *   LinkedIn: linkedin.com/developers   → Create App → Auth
 *   GitHub  : github.com/settings/developers → New OAuth App
 *             Callback: http://localhost:8080/api/auth/github/callback
 */
@RestController
@RequestMapping("/api/auth")
public class SocialAuthController {

    // ── Credentials — set via environment variables ───────────────────────
    static final String LINKEDIN_CLIENT_ID     = env("LINKEDIN_CLIENT_ID",     "");
    static final String LINKEDIN_CLIENT_SECRET = env("LINKEDIN_CLIENT_SECRET", "");
    static final String GITHUB_CLIENT_ID       = env("GITHUB_CLIENT_ID",       "");
    static final String GITHUB_CLIENT_SECRET   = env("GITHUB_CLIENT_SECRET",   "");
    static final String APP_BASE_URL           = env("APP_BASE_URL", "http://localhost:8080")
                                                     .replaceAll("/+$", "");

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
    // ─────────────────────────────────────────────────────────────────────

    private static final String PENDING_KEY = "pending_social_auth";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public SocialAuthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ══════════════════════════════════════════════════════════════════════
    // GOOGLE — client-side GSI flow
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/google")
    public ResponseEntity<Map<String, Object>> googleAuth(
            @RequestBody Map<String, Object> body, HttpSession session) {

        String credential = s(body, "credential");
        if (credential.isEmpty()) return err(400, "Missing credential.");

        Map<?, ?> payload;
        try {
            String[] parts = credential.split("\\.");
            byte[] decoded = Base64.getUrlDecoder().decode(pad(parts[1]));
            payload = mapper.readValue(decoded, Map.class);
        } catch (Exception e) { return err(400, "Could not decode credential."); }

        String googleId  = str(payload, "sub");
        String email     = str(payload, "email");
        String firstName = str(payload, "given_name");
        String lastName  = str(payload, "family_name");
        if (firstName.isEmpty()) firstName = str(payload, "name");
        if (googleId.isEmpty() || email.isEmpty())
            return err(400, "Google credential missing required fields.");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM users WHERE google_id = ? OR (google_id = '' AND linkedin_id = '' AND github_id = '' AND email = ?)",
            googleId, email);

        if (!rows.isEmpty()) {
            Map<String, Object> u = rows.get(0);
            String uid = (String) u.get("id");
            if ("".equals(u.getOrDefault("google_id", "")))
                jdbc.update("UPDATE users SET google_id = ? WHERE id = ?", googleId, uid);
            session.setAttribute("userId", uid);
            return ResponseEntity.ok(buildUserResponse(uid));
        }

        String role = s(body, "role");
        if (role.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "needsRole", true, "email", email,
                "firstName", firstName, "lastName", lastName));
        }
        if (!role.equals("student") && !role.equals("pro"))
            return err(400, "Role must be 'student' or 'pro'.");

        String uid = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO users (id,email,password_hash,role,first_name,last_name,google_id) VALUES (?,?,?,?,?,?,?)",
            uid, email, "", role, firstName, lastName, googleId);
        jdbc.update("INSERT INTO profiles (user_id) VALUES (?)", uid);
        session.setAttribute("userId", uid);
        return ResponseEntity.ok(buildUserResponse(uid));
    }

    // ══════════════════════════════════════════════════════════════════════
    // LINKEDIN — server-side redirect flow
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/linkedin")
    public void linkedinInit(HttpSession session, HttpServletResponse res) throws Exception {
        if (LINKEDIN_CLIENT_ID.isEmpty()) {
            res.sendRedirect("/?social_error=" + encode("LinkedIn credentials not configured. Set LINKEDIN_CLIENT_ID and LINKEDIN_CLIENT_SECRET in SocialAuthController.java, then rebuild."));
            return;
        }
        String state = UUID.randomUUID().toString();
        session.setAttribute("oauth_state_linkedin", state);
        res.sendRedirect(
            "https://www.linkedin.com/oauth/v2/authorization" +
            "?response_type=code" +
            "&client_id=" + LINKEDIN_CLIENT_ID +
            "&redirect_uri=" + encode(APP_BASE_URL + "/api/auth/linkedin/callback") +
            "&state=" + state +
            "&scope=openid%20profile%20email");
    }

    @GetMapping("/linkedin/callback")
    public void linkedinCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session, HttpServletResponse res) throws Exception {

        if (error != null || code == null) {
            res.sendRedirect("/?social_error=" + encode("LinkedIn sign-in was cancelled."));
            return;
        }
        String expected = (String) session.getAttribute("oauth_state_linkedin");
        if (expected == null || !expected.equals(state)) {
            res.sendRedirect("/?social_error=" + encode("Invalid OAuth state. Please try again."));
            return;
        }
        session.removeAttribute("oauth_state_linkedin");

        // Exchange code for access token
        String tokenBody =
            "grant_type=authorization_code" +
            "&code=" + encode(code) +
            "&redirect_uri=" + encode(APP_BASE_URL + "/api/auth/linkedin/callback") +
            "&client_id=" + encode(LINKEDIN_CLIENT_ID) +
            "&client_secret=" + encode(LINKEDIN_CLIENT_SECRET);

        HttpRequest tokenReq = HttpRequest.newBuilder()
            .uri(URI.create("https://www.linkedin.com/oauth/v2/accessToken"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
            .build();
        Map<?, ?> tokenData = jsonGet(tokenReq);
        String token = str(tokenData, "access_token");
        if (token.isEmpty()) {
            String liError = str(tokenData, "error") + ": " + str(tokenData, "error_description");
            res.sendRedirect("/?social_error=" + encode("LinkedIn token failed: " + liError));
            return;
        }

        // Fetch profile via OIDC userinfo
        HttpRequest profileReq = HttpRequest.newBuilder()
            .uri(URI.create("https://api.linkedin.com/v2/userinfo"))
            .header("Authorization", "Bearer " + token)
            .GET().build();
        Map<?, ?> profile = jsonGet(profileReq);

        String linkedinId = str(profile, "sub");
        String email      = str(profile, "email");
        String firstName  = str(profile, "given_name");
        String lastName   = str(profile, "family_name");

        handleRedirectSocial(session, res, "linkedin", linkedinId, email, firstName, lastName);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GITHUB — server-side redirect flow
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/github")
    public void githubInit(HttpSession session, HttpServletResponse res) throws Exception {
        if (GITHUB_CLIENT_ID.isEmpty()) {
            res.sendRedirect("/?social_error=" + encode("GitHub credentials not configured. Set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET in SocialAuthController.java, then rebuild."));
            return;
        }
        String state = UUID.randomUUID().toString();
        session.setAttribute("oauth_state_github", state);
        res.sendRedirect(
            "https://github.com/login/oauth/authorize" +
            "?client_id=" + GITHUB_CLIENT_ID +
            "&redirect_uri=" + encode(APP_BASE_URL + "/api/auth/github/callback") +
            "&scope=read%3Auser%20user%3Aemail" +
            "&state=" + state);
    }

    @GetMapping("/github/callback")
    public void githubCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session, HttpServletResponse res) throws Exception {

        if (error != null || code == null) {
            res.sendRedirect("/?social_error=" + encode("GitHub sign-in was cancelled."));
            return;
        }
        String expected = (String) session.getAttribute("oauth_state_github");
        if (expected == null || !expected.equals(state)) {
            res.sendRedirect("/?social_error=" + encode("Invalid OAuth state. Please try again."));
            return;
        }
        session.removeAttribute("oauth_state_github");

        // Exchange code for access token
        HttpRequest tokenReq = HttpRequest.newBuilder()
            .uri(URI.create("https://github.com/login/oauth/access_token"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"client_id\":\"" + GITHUB_CLIENT_ID + "\"," +
                "\"client_secret\":\"" + GITHUB_CLIENT_SECRET + "\"," +
                "\"code\":\"" + code + "\"," +
                "\"redirect_uri\":\"" + APP_BASE_URL + "/api/auth/github/callback\"}"))
            .build();
        Map<?, ?> tokenData = jsonGet(tokenReq);
        String token = str(tokenData, "access_token");
        if (token.isEmpty()) {
            res.sendRedirect("/?social_error=" + encode("GitHub token exchange failed. Check your credentials."));
            return;
        }

        // Fetch profile
        HttpRequest profileReq = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/user"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .GET().build();
        Map<?, ?> profile = jsonGet(profileReq);

        String githubId   = str(profile, "id");
        String email      = str(profile, "email");
        String name       = str(profile, "name");
        String login      = str(profile, "login");

        // GitHub email may be null if private — fetch from /user/emails
        if (email.isEmpty()) {
            HttpRequest emailReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/user/emails"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET().build();
            HttpResponse<String> emailResp = http.send(emailReq, HttpResponse.BodyHandlers.ofString());
            List<?> emails = mapper.readValue(emailResp.body(), List.class);
            for (Object item : emails) {
                Map<?,?> em = (Map<?,?>) item;
                if (Boolean.TRUE.equals(em.get("primary")) && Boolean.TRUE.equals(em.get("verified"))) {
                    email = str(em, "email"); break;
                }
            }
        }
        if (email.isEmpty()) {
            res.sendRedirect("/?social_error=" + encode("Could not retrieve email from GitHub. Please make your email public in GitHub settings."));
            return;
        }

        // Split display name into first/last
        String firstName = name.contains(" ") ? name.substring(0, name.lastIndexOf(' ')).trim() : name;
        String lastName  = name.contains(" ") ? name.substring(name.lastIndexOf(' ') + 1).trim() : login;
        if (firstName.isEmpty()) { firstName = login; lastName = ""; }

        handleRedirectSocial(session, res, "github", githubId, email, firstName, lastName);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SHARED — pending role pick + account creation
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/social/pending")
    public ResponseEntity<Map<String, Object>> getPending(HttpSession session) {
        @SuppressWarnings("unchecked")
        Map<String, String> p = (Map<String, String>) session.getAttribute(PENDING_KEY);
        if (p == null) return err(404, "No pending social auth.");
        Map<String, Object> resp = new LinkedHashMap<>(p);
        resp.put("needsRole", true);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/social/complete")
    public ResponseEntity<Map<String, Object>> completeRedirect(
            @RequestBody Map<String, Object> body, HttpSession session) {

        @SuppressWarnings("unchecked")
        Map<String, String> p = (Map<String, String>) session.getAttribute(PENDING_KEY);
        if (p == null) return err(400, "No pending social auth session.");

        String role = s(body, "role");
        if (!role.equals("student") && !role.equals("pro"))
            return err(400, "Role must be 'student' or 'pro'.");

        String provider   = p.get("provider");
        String providerId = p.get("providerId");
        String email      = p.get("email");
        String firstName  = p.getOrDefault("firstName", "");
        String lastName   = p.getOrDefault("lastName", "");
        String idCol      = providerCol(provider);

        String uid = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO users (id,email,password_hash,role,first_name,last_name," + idCol + ") VALUES (?,?,?,?,?,?,?)",
            uid, email, "", role, firstName, lastName, providerId);
        jdbc.update("INSERT INTO profiles (user_id) VALUES (?)", uid);

        session.removeAttribute(PENDING_KEY);
        session.setAttribute("userId", uid);
        return ResponseEntity.ok(buildUserResponse(uid));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void handleRedirectSocial(
            HttpSession session, HttpServletResponse res,
            String provider, String providerId, String email,
            String firstName, String lastName) throws Exception {

        String idCol = providerCol(provider);
        List<Map<String,Object>> rows = jdbc.queryForList(
            "SELECT * FROM users WHERE " + idCol + " = ? OR (" + idCol + " = '' AND google_id = '' AND linkedin_id = '' AND github_id = '' AND email = ?)",
            providerId, email);

        if (!rows.isEmpty()) {
            String uid = (String) rows.get(0).get("id");
            if ("".equals(rows.get(0).getOrDefault(idCol, "")))
                jdbc.update("UPDATE users SET " + idCol + " = ? WHERE id = ?", providerId, uid);
            session.setAttribute("userId", uid);
            res.sendRedirect("/?social=success");
            return;
        }

        Map<String, String> pending = new HashMap<>();
        pending.put("provider",   provider);
        pending.put("providerId", providerId);
        pending.put("email",      email);
        pending.put("firstName",  firstName);
        pending.put("lastName",   lastName);
        session.setAttribute(PENDING_KEY, pending);
        res.sendRedirect("/?social=pending");
    }

    private Map<String, Object> buildUserResponse(String uid) {
        Map<String, Object> u = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", uid);
        List<String> skills = jdbc
            .queryForList("SELECT skill_name FROM skills WHERE user_id = ? ORDER BY id", uid)
            .stream().map(r -> (String) r.get("skill_name")).collect(Collectors.toList());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",        u.get("id"));
        r.put("email",     s2(u, "email"));
        r.put("firstName", s2(u, "first_name"));
        r.put("lastName",  s2(u, "last_name"));
        r.put("name",      (s2(u, "first_name") + " " + s2(u, "last_name")).trim());
        r.put("role",      s2(u, "role"));
        r.put("skills",    skills);
        return r;
    }

    private Map<?, ?> jsonGet(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(resp.body(), Map.class);
    }

    private static String providerCol(String provider) {
        return switch (provider) {
            case "linkedin" -> "linkedin_id";
            case "github"   -> "github_id";
            default         -> "google_id";
        };
    }

    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String pad(String s) {
        return switch (s.length() % 4) { case 2 -> s + "=="; case 3 -> s + "="; default -> s; };
    }
    private static String s(Map<?,?> m, String k)  { Object v = m.get(k); return v == null ? "" : v.toString().trim(); }
    private static String s2(Map<?,?> m, String k) { Object v = m.get(k); return v == null ? "" : v.toString(); }
    private static String str(Map<?,?> m, String k){ Object v = m.get(k); return v == null ? "" : v.toString(); }
    private static ResponseEntity<Map<String, Object>> err(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
