package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.dto.TaskCommentResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.TaskCommentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskCommentController {

    private final TaskCommentService taskCommentService;

    public TaskCommentController(TaskCommentService taskCommentService) {
        this.taskCommentService = taskCommentService;
    }

    @GetMapping("/{taskKey}/comments")
    public ResponseEntity<ApiResponse<List<TaskCommentResponse>>> list(
            HttpServletRequest request,
            @PathVariable("taskKey") String taskKey) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(taskCommentService.listByTaskKey(taskKey, userId)));
    }

    @PostMapping("/{taskKey}/comments")
    public ResponseEntity<ApiResponse<TaskCommentResponse>> create(
            HttpServletRequest request,
            @PathVariable("taskKey") String taskKey,
            @RequestBody CreateTaskCommentRequest body) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(taskCommentService.create(taskKey, userId, body)));
    }

    @DeleteMapping("/{taskKey}/comments/{commentId}")
    public ResponseEntity<Void> delete(
            HttpServletRequest request,
            @PathVariable("taskKey") String taskKey,
            @PathVariable("commentId") Long commentId) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        taskCommentService.delete(taskKey, commentId, userId);
        return ResponseEntity.noContent().build();
    }
}
