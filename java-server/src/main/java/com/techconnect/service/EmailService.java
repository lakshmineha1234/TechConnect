package com.techconnect.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Sends transactional email via Resend's HTTP API (https://resend.com).
 * Set RESEND_API_KEY and MAIL_FROM env vars on Render.
 * MAIL_FROM must be a verified sender — use "onboarding@resend.dev" for
 * sandbox testing (only delivers to the Resend account owner's email).
 */
@Service
public class EmailService {

    @Value("${resend.api-key:}")
    private String apiKey;

    @Value("${resend.from:TechConnect <onboarding@resend.dev>}")
    private String from;

    private final HttpClient http = HttpClient.newHttpClient();

    @Async
    public void send(String to, String subject, String text) {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[EmailService] RESEND_API_KEY not set — skipping email to " + to);
            return;
        }

        String body = """
                {"from":"%s","to":["%s"],"subject":"%s","text":"%s"}
                """.formatted(
                        esc(from), esc(to), esc(subject), esc(text)).strip();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                System.err.println("[EmailService] Resend error " + resp.statusCode() + ": " + resp.body());
            }
        } catch (Exception e) {
            System.err.println("[EmailService] Failed to send email to " + to + ": " + e.getMessage());
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
