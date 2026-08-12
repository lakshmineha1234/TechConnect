package com.techconnect.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves all static assets from the TechConnect project root (parent of
 * java-server/) and falls back to index.html for any unknown path — enabling
 * the single-page application to handle its own client-side routing.
 *
 * This controller has the lowest Spring MVC precedence: API controllers
 * registered at /api/** will always take priority.
 */
@Controller
public class StaticController {

    /** Resolved once at startup: the TechConnect root directory. */
    private final Path baseDir = Paths.get("").toAbsolutePath()
                                       .getParent()
                                       .normalize();

    @GetMapping("/**")
    public ResponseEntity<Resource> serve(HttpServletRequest req) {
        String uri = req.getRequestURI().replaceFirst("^/", "");

        // Never intercept API or WebSocket routes
        if (uri.startsWith("api/") || uri.startsWith("ws/") || uri.equals("ws")) {
            return ResponseEntity.notFound().build();
        }

        // Try to serve the requested file
        if (!uri.isEmpty()) {
            File candidate = baseDir.resolve(uri).normalize().toFile();
            try {
                // Security: prevent path traversal outside the base directory
                if (candidate.getCanonicalPath().startsWith(baseDir.toString())
                        && candidate.isFile()) {
                    return ResponseEntity.ok()
                            .contentType(mediaTypeFor(uri))
                            .body(new FileSystemResource(candidate));
                }
            } catch (IOException ignored) {
                // Fall through to index.html
            }
        }

        // SPA fallback → index.html
        File index = baseDir.resolve("index.html").toFile();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new FileSystemResource(index));
    }

    private static MediaType mediaTypeFor(String path) {
        if (path.endsWith(".html")) return MediaType.TEXT_HTML;
        if (path.endsWith(".css"))  return MediaType.parseMediaType("text/css");
        if (path.endsWith(".js"))   return MediaType.parseMediaType("application/javascript");
        if (path.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (path.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (path.endsWith(".svg"))  return MediaType.parseMediaType("image/svg+xml");
        if (path.endsWith(".ico"))  return MediaType.parseMediaType("image/x-icon");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
