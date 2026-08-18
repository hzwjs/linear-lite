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
import com.linearlite.server.service.AgentTaskEventService;
import com.linearlite.server.service.AgentTaskOrchestrationService;
import com.linearlite.server.service.BridgeExecutionAttachmentService;
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

/** Bridge 专用 API；认证使用当前浏览器建立的一次性执行绑定。 */
@RestController
@RequestMapping("/api/bridge")
public class AgentController {
    private final BridgeExecutionAttachmentService attachmentService;
    private final AgentTaskOrchestrationService orchestrationService;
    private final TaskCommentService taskCommentService;
    private final AgentTaskEventService eventService;

    public AgentController(
            AgentTaskOrchestrationService orchestrationService,
            TaskCommentService taskCommentService,
            AgentTaskEventService eventService,
            BridgeExecutionAttachmentService attachmentService) {
        this.orchestrationService = orchestrationService;
        this.taskCommentService = taskCommentService;
        this.eventService = eventService;
        this.attachmentService = attachmentService;
    }

    @PostMapping("/jobs/claim")
    public ResponseEntity<ApiResponse<AgentJobClaimResponse>> claim(@RequestHeader("X-Execution-Attachment") String token) {
        Long ownerUserId = attachmentService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.claim(ownerUserId)));
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<AgentProjectResponse>>> projects(
            @RequestHeader("X-Execution-Attachment") String token) {
        Long ownerUserId = attachmentService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.listEnabledProjects(ownerUserId)));
    }

    @PostMapping("/jobs/{jobId}/heartbeat")
    public ResponseEntity<ApiResponse<Void>> heartbeat(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable Long jobId,
            @RequestBody AgentHeartbeatRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        orchestrationService.heartbeat(ownerUserId, jobId, request.getExecutionId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/jobs/{jobId}/events")
    public ResponseEntity<ApiResponse<Void>> events(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable Long jobId,
            @RequestBody AgentTaskEventBatchRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        eventService.report(ownerUserId, jobId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    /** Bridge 轮询该接口即可及时发现人类用户取消了当前 Job。 */
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<ApiResponse<AgentTaskStatusResponse>> jobStatus(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable Long jobId,
            @RequestParam String executionId) {
        Long ownerUserId = attachmentService.authenticate(token, executionId);
        return ResponseEntity.ok(ApiResponse.success(
                orchestrationService.getAgentJobStatus(ownerUserId, jobId, executionId)));
    }

    @PostMapping("/jobs/{jobId}/succeeded")
    public ResponseEntity<ApiResponse<Void>> succeeded(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable Long jobId,
            @RequestBody AgentJobResultRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        orchestrationService.succeed(ownerUserId, jobId, request.getExecutionId(), request.getResult(),
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
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable Long jobId,
            @RequestBody AgentJobResultRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        orchestrationService.fail(ownerUserId, jobId, request.getExecutionId(), request.getErrorMessage(),
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
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String executionId) {
        Long ownerUserId = attachmentService.authenticate(token, executionId);
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.getSession(ownerUserId, executionId)));
    }

    @PostMapping("/sessions/{executionId}/state")
    public ResponseEntity<ApiResponse<Void>> state(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String executionId,
            @RequestBody AgentSessionStateRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, executionId);
        orchestrationService.updateSessionState(ownerUserId, executionId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/sessions/{executionId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String executionId) {
        Long ownerUserId = attachmentService.authenticate(token, executionId);
        orchestrationService.cancel(ownerUserId, executionId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
