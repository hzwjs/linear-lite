package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.AgentSessionStreamService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tasks")
public class TaskAgentSessionController {
    private final AgentSessionStreamService sessionStreamService;

    public TaskAgentSessionController(AgentSessionStreamService sessionStreamService) {
        this.sessionStreamService = sessionStreamService;
    }

    @GetMapping(path = "/{taskKey}/local-pi/sessions/{executionId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            HttpServletRequest request,
            @PathVariable String taskKey,
            @PathVariable String executionId) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        SseEmitter emitter = sessionStreamService.stream(taskKey, userId, executionId);
        try {
            emitter.send(SseEmitter.event().name("ready").data("connected"));
        } catch (java.io.IOException error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    @PostMapping("/{taskKey}/local-pi/sessions/{executionId}/snapshot-requests")
    public ResponseEntity<ApiResponse<String>> requestSnapshot(
            HttpServletRequest request,
            @PathVariable String taskKey,
            @PathVariable String executionId) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(
                sessionStreamService.requestSnapshot(taskKey, userId, executionId)));
    }
}
