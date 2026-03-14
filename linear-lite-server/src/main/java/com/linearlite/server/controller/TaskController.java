package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateTaskRequest;
import com.linearlite.server.dto.TaskActivityResponse;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.TaskActivityService;
import com.linearlite.server.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务 API。需 JWT 保护；当前用户从请求属性 {@link JwtAuthFilter#REQUEST_ATTR_USER_ID} 解析。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskActivityService taskActivityService;

    public TaskController(TaskService taskService, TaskActivityService taskActivityService) {
        this.taskService = taskService;
        this.taskActivityService = taskActivityService;
    }

    /**
     * 按项目 ID 返回任务列表。支持仅顶层或按父任务过滤。
     *
     * @param projectId    必填，项目 ID
     * @param topLevelOnly 为 true 时仅返回顶层任务（parent_id IS NULL）
     * @param parentId     非空时仅返回该父任务下的直接子任务
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Task>>> list(
            HttpServletRequest request,
            @RequestParam Long projectId,
            @RequestParam(required = false) Boolean topLevelOnly,
            @RequestParam(required = false) Long parentId) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        List<Task> list = taskService.listByProjectId(projectId, topLevelOnly, parentId, userId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/favorites")
    public ResponseEntity<ApiResponse<List<Task>>> listFavorites(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(taskService.listFavorites(userId)));
    }

    @GetMapping("/{id}/activities")
    public ResponseEntity<ApiResponse<List<TaskActivityResponse>>> listActivities(@PathVariable("id") String taskKey) {
        return ResponseEntity.ok(ApiResponse.success(taskActivityService.listByTaskKey(taskKey)));
    }

    /**
     * 创建任务。后端绑定当前登录用户为 creator_id，并生成带项目前缀的 task_key（如 PROD-1、ENG-2）。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Task>> create(
            HttpServletRequest request,
            @RequestBody CreateTaskRequest body) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        Task created = taskService.create(
                body.getProjectId(),
                userId,
                body.getParentId(),
                body.getTitle(),
                body.getDescription(),
                body.getStatus(),
                body.getPriority(),
                body.getAssigneeId(),
                body.getDueDate());
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    /**
     * 更新任务。{id} 为任务对外 ID（task_key，如 ENG-1）。仅更新请求体中提供的字段。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Task>> update(
            HttpServletRequest request,
            @PathVariable("id") String taskKey,
            @RequestBody UpdateTaskRequest body) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        Task updated = taskService.update(taskKey, body, userId);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PostMapping("/{id}/favorite")
    public ResponseEntity<ApiResponse<Task>> addFavorite(
            HttpServletRequest request,
            @PathVariable("id") String taskKey) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(taskService.addFavorite(taskKey, userId)));
    }

    @DeleteMapping("/{id}/favorite")
    public ResponseEntity<ApiResponse<Task>> removeFavorite(
            HttpServletRequest request,
            @PathVariable("id") String taskKey) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(taskService.removeFavorite(taskKey, userId)));
    }
}
