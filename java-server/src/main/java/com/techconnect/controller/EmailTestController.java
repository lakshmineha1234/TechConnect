package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Temporary debug endpoint — remove after email is confirmed working. */
@RestController
public class EmailTestController {

    @Value("${resend.api-key:}") private String apiKey;
    @Value("${resend.from:TechConnect <onboarding@resend.dev>}") private String from;

    private final HttpClient http = HttpClient.newHttpClient();

    @GetMapping("/api/admin/email-test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam String to, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));

        if (apiKey == null || apiKey.isBlank())
            return ResponseEntity.ok(Map.of("error", "RESEND_API_KEY is not set", "apiKeyLength", 0));

        String body = """
                {"from":"%s","to":["%s"],"subject":"TechConnect email test","text":"If you receive this, Resend is working!"}
                """.formatted(esc(from), esc(to)).strip();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.ok(Map.of(
                    "status",       resp.statusCode(),
                    "body",         resp.body(),
                    "apiKeyPrefix", apiKey.substring(0, Math.min(8, apiKey.length())) + "…",
                    "from",         from
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    private static String esc(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }
}
