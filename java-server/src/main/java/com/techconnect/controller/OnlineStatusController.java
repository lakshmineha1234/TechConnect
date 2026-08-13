package com.techconnect.controller;

import com.techconnect.service.OnlineStatusService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * GET /api/users/online   — returns set of currently online user IDs
 */
@RestController
public class OnlineStatusController {

    private final OnlineStatusService onlineStatus;

    public OnlineStatusController(OnlineStatusService onlineStatus) {
        this.onlineStatus = onlineStatus;
    }

    @GetMapping("/api/users/online")
    public ResponseEntity<Set<String>> online(HttpSession session) {
        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();
        return ResponseEntity.ok(onlineStatus.getOnlineUsers());
    }
}
