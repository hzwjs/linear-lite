package com.linearlite.server.controller;

import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.AgentTaskEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tasks")
public class TaskAgentEventController {
    private final AgentTaskEventService eventService;

    public TaskAgentEventController(AgentTaskEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping(path = "/{taskKey}/agent-events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            HttpServletRequest request,
            @PathVariable String taskKey,
            @RequestParam String executionId) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        SseEmitter emitter = eventService.stream(taskKey, userId, executionId);
        try {
            emitter.send(SseEmitter.event().name("ready").data("connected"));
        } catch (java.io.IOException error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }
}
