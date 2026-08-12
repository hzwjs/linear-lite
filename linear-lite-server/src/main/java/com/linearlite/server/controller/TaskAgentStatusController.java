package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.AgentTaskCancelRequest;
import com.linearlite.server.dto.AgentTaskStatusResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.AgentTaskOrchestrationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskAgentStatusController {
    private final AgentTaskOrchestrationService orchestrationService;

    public TaskAgentStatusController(AgentTaskOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @GetMapping("/{taskKey}/agent-status")
    public ResponseEntity<ApiResponse<AgentTaskStatusResponse>> get(
            HttpServletRequest request, @PathVariable String taskKey) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.getTaskStatus(taskKey, userId)));
    }

    /** 人类用户只能取消自己有权限访问的任务，具体会话状态变更由编排服务统一处理。 */
    @PostMapping("/{taskKey}/agent-cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(
            HttpServletRequest request,
            @PathVariable String taskKey,
            @RequestBody AgentTaskCancelRequest cancelRequest) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        orchestrationService.cancelTask(userId, taskKey, cancelRequest.getExecutionId());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
