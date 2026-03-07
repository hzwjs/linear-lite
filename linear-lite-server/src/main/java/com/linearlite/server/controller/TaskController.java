package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateTaskRequest;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 按项目 ID 返回任务列表。
     *
     * @param projectId 必填，项目 ID
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Task>>> list(
            @RequestParam Long projectId) {
        List<Task> list = taskService.listByProjectId(projectId);
        return ResponseEntity.ok(ApiResponse.success(list));
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
            @PathVariable("id") String taskKey,
            @RequestBody UpdateTaskRequest body) {
        Task updated = taskService.update(taskKey, body);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }
}
