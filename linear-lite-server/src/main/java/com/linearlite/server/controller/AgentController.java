package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.AgentHeartbeatRequest;
import com.linearlite.server.dto.AgentJobClaimResponse;
import com.linearlite.server.dto.AgentJobResultRequest;
import com.linearlite.server.dto.AgentProjectResponse;
import com.linearlite.server.dto.AgentSessionResponse;
import com.linearlite.server.dto.AgentSessionStateRequest;
import com.linearlite.server.dto.AgentTaskEventBatchRequest;
import com.linearlite.server.dto.AgentTaskStatusResponse;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.entity.User;
import com.linearlite.server.service.AgentAuthenticationService;
import com.linearlite.server.service.AgentTaskEventService;
import com.linearlite.server.service.AgentTaskOrchestrationService;
import com.linearlite.server.service.TaskCommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Pi Bridge 专用 API；认证使用独立 Agent Token，不进入人类 JWT 链路。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentAuthenticationService authenticationService;
    private final AgentTaskOrchestrationService orchestrationService;
    private final TaskCommentService taskCommentService;
    private final AgentTaskEventService eventService;

    public AgentController(
            AgentAuthenticationService authenticationService,
            AgentTaskOrchestrationService orchestrationService,
            TaskCommentService taskCommentService,
            AgentTaskEventService eventService) {
        this.authenticationService = authenticationService;
        this.orchestrationService = orchestrationService;
        this.taskCommentService = taskCommentService;
        this.eventService = eventService;
    }

    @PostMapping("/jobs/claim")
    public ResponseEntity<ApiResponse<AgentJobClaimResponse>> claim(@RequestHeader("X-Agent-Token") String token) {
        User agent = authenticationService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.claim(agent.getId())));
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<AgentProjectResponse>>> projects(
            @RequestHeader("X-Agent-Token") String token) {
        User agent = authenticationService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.listEnabledProjects(agent.getId())));
    }

    @PostMapping("/jobs/{jobId}/heartbeat")
    public ResponseEntity<ApiResponse<Void>> heartbeat(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable Long jobId,
            @RequestBody AgentHeartbeatRequest request) {
        User agent = authenticationService.authenticate(token);
        orchestrationService.heartbeat(agent.getId(), jobId, request.getExecutionId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/jobs/{jobId}/events")
    public ResponseEntity<ApiResponse<Void>> events(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable Long jobId,
            @RequestBody AgentTaskEventBatchRequest request) {
        User agent = authenticationService.authenticate(token);
        eventService.report(agent.getId(), jobId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    /** Bridge 轮询该接口即可及时发现人类用户取消了当前 Job。 */
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<ApiResponse<AgentTaskStatusResponse>> jobStatus(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable Long jobId,
            @RequestParam String executionId) {
        User agent = authenticationService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(
                orchestrationService.getAgentJobStatus(agent.getId(), jobId, executionId)));
    }

    @PostMapping("/jobs/{jobId}/succeeded")
    public ResponseEntity<ApiResponse<Void>> succeeded(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable Long jobId,
            @RequestBody AgentJobResultRequest request) {
        User agent = authenticationService.authenticate(token);
        orchestrationService.succeed(agent.getId(), jobId, request.getExecutionId(), request.getResult(),
                (taskKey, authorId, body) -> {
                    CreateTaskCommentRequest comment = new CreateTaskCommentRequest();
                    comment.setBody(body);
                    comment.setMentionedUserIds(java.util.List.of());
                    taskCommentService.create(taskKey, authorId, comment);
                });
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/jobs/{jobId}/failed")
    public ResponseEntity<ApiResponse<Void>> failed(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable Long jobId,
            @RequestBody AgentJobResultRequest request) {
        User agent = authenticationService.authenticate(token);
        orchestrationService.fail(agent.getId(), jobId, request.getExecutionId(), request.getErrorMessage(),
                (taskKey, authorId, body) -> {
                    CreateTaskCommentRequest comment = new CreateTaskCommentRequest();
                    comment.setBody(body);
                    comment.setMentionedUserIds(java.util.List.of());
                    taskCommentService.create(taskKey, authorId, comment);
                });
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/sessions/{executionId}")
    public ResponseEntity<ApiResponse<AgentSessionResponse>> session(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String executionId) {
        User agent = authenticationService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.getSession(agent.getId(), executionId)));
    }

    @PostMapping("/sessions/{executionId}/state")
    public ResponseEntity<ApiResponse<Void>> state(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String executionId,
            @RequestBody AgentSessionStateRequest request) {
        User agent = authenticationService.authenticate(token);
        orchestrationService.updateSessionState(agent.getId(), executionId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/sessions/{executionId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @RequestHeader("X-Agent-Token") String token,
            @PathVariable String executionId) {
        User agent = authenticationService.authenticate(token);
        orchestrationService.cancel(agent.getId(), executionId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
