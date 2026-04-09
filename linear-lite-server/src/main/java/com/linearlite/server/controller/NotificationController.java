package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.InAppNotificationResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.InAppNotificationService;
import com.linearlite.server.service.NotificationSseBroadcaster;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final InAppNotificationService inAppNotificationService;
    private final NotificationSseBroadcaster notificationSseBroadcaster;

    public NotificationController(
            InAppNotificationService inAppNotificationService,
            NotificationSseBroadcaster notificationSseBroadcaster) {
        this.inAppNotificationService = inAppNotificationService;
        this.notificationSseBroadcaster = notificationSseBroadcaster;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InAppNotificationResponse>>> list(
            HttpServletRequest request,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "30") int limit,
            @RequestParam(required = false) Boolean unreadOnly) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(inAppNotificationService.list(userId, beforeId, limit, unreadOnly)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        Map<String, Long> body = new HashMap<>();
        body.put("count", inAppNotificationService.countUnread(userId));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(HttpServletRequest request, @PathVariable("id") Long id) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        inAppNotificationService.markRead(userId, id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllRead(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        inAppNotificationService.markAllRead(userId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter notificationStream(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return notificationSseBroadcaster.register(userId);
    }
}
