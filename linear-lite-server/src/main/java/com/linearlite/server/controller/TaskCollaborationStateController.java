package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.TaskCollaborationState;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.TaskCollaborationStateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskCollaborationStateController {
    private final TaskCollaborationStateService service;
    public TaskCollaborationStateController(TaskCollaborationStateService service) { this.service = service; }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson(HttpMessageNotReadableException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, "协作参数JSON无效"));
    }

    @PostMapping("/api/collaboration/tasks/state")
    public ResponseEntity<ApiResponse<TaskCollaborationState>> get(HttpServletRequest request,
            @RequestBody TaskCollaborationState.Request input) {
        var state = service.get((Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID), input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Linear-Lite-Server-Time", state.serverTime().toString()).body(ApiResponse.success(state));
    }
}
