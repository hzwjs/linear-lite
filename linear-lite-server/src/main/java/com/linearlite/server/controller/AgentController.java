package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.AgentHeartbeatRequest;
import com.linearlite.server.dto.AgentJobClaimResponse;
import com.linearlite.server.dto.AgentJobResultRequest;
import com.linearlite.server.dto.AgentProjectResponse;
import com.linearlite.server.dto.AgentSessionResponse;
import com.linearlite.server.dto.AgentSessionStateRequest;
import com.linearlite.server.dto.AgentSessionReadErrorRequest;
import com.linearlite.server.dto.AgentSessionSnapshot;
import com.linearlite.server.dto.AgentSessionSnapshotClaimResponse;
import com.linearlite.server.dto.RuntimeDisplayBlockBatchRequest;
import com.linearlite.server.dto.AgentTaskStatusResponse;
import com.linearlite.server.dto.PiSettingsErrorRequest;
import com.linearlite.server.dto.PiSettingsRequestClaimResponse;
import com.linearlite.server.dto.PiSettingsState;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.service.AgentSessionStreamService;
import com.linearlite.server.service.AgentTaskOrchestrationService;
import com.linearlite.server.service.BridgeExecutionAttachmentService;
import com.linearlite.server.service.TaskCommentService;
import com.linearlite.server.service.PiSettingsRequestService;
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
    private final AgentSessionStreamService sessionStreamService;
    private final PiSettingsRequestService settingsRequestService;

    public AgentController(
            AgentTaskOrchestrationService orchestrationService,
            TaskCommentService taskCommentService,
            AgentSessionStreamService sessionStreamService,
            PiSettingsRequestService settingsRequestService,
            BridgeExecutionAttachmentService attachmentService) {
        this.orchestrationService = orchestrationService;
        this.taskCommentService = taskCommentService;
        this.sessionStreamService = sessionStreamService;
        this.settingsRequestService = settingsRequestService;
        this.attachmentService = attachmentService;
    }

    @PostMapping("/jobs/claim")
    public ResponseEntity<ApiResponse<AgentJobClaimResponse>> claim(@RequestHeader("X-Execution-Attachment") String token) {
        Long ownerUserId = attachmentService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(
                orchestrationService.claim(ownerUserId, attachmentService.executionId(token))));
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

    @PostMapping("/jobs/{jobId}/runtime-display-blocks")
    public ResponseEntity<ApiResponse<Void>> runtimeDisplayBlocks(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable Long jobId,
            @RequestBody RuntimeDisplayBlockBatchRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        sessionStreamService.reportRuntime(ownerUserId, jobId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/session-snapshot-requests/claim")
    public ResponseEntity<ApiResponse<AgentSessionSnapshotClaimResponse>> claimSessionSnapshot(
            @RequestHeader("X-Execution-Attachment") String token) {
        Long ownerUserId = attachmentService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(sessionStreamService.claimSnapshot(
                ownerUserId, attachmentService.executionId(token))));
    }

    @PostMapping("/session-snapshot-requests/{requestId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeSessionSnapshot(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String requestId,
            @RequestBody AgentSessionSnapshot snapshot) {
        Long ownerUserId = attachmentService.authenticate(token, snapshot.executionId());
        sessionStreamService.completeSnapshot(ownerUserId, requestId, snapshot);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/session-snapshot-requests/{requestId}/fail")
    public ResponseEntity<ApiResponse<Void>> failSessionSnapshot(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String requestId,
            @RequestBody AgentSessionReadErrorRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        sessionStreamService.failSnapshot(ownerUserId, requestId,
                request.getExecutionId(), request.getErrorMessage());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/settings-requests/claim")
    public ResponseEntity<ApiResponse<PiSettingsRequestClaimResponse>> claimSettings(
            @RequestHeader("X-Execution-Attachment") String token) {
        Long ownerUserId = attachmentService.authenticate(token);
        return ResponseEntity.ok(ApiResponse.success(settingsRequestService.claim(
                ownerUserId, attachmentService.executionId(token))));
    }

    @PostMapping("/settings-requests/{requestId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeSettings(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String requestId,
            @RequestBody PiSettingsState state) {
        Long ownerUserId = attachmentService.authenticate(token, state.executionId());
        settingsRequestService.complete(ownerUserId, requestId, state);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/settings-requests/{requestId}/fail")
    public ResponseEntity<ApiResponse<Void>> failSettings(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String requestId,
            @RequestBody PiSettingsErrorRequest request) {
        Long ownerUserId = attachmentService.authenticate(token, request.getExecutionId());
        settingsRequestService.fail(ownerUserId, requestId, request.getExecutionId(), request.getErrorMessage());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/sessions/{executionId}/snapshot")
    public ResponseEntity<ApiResponse<Void>> publishSessionSnapshot(
            @RequestHeader("X-Execution-Attachment") String token,
            @PathVariable String executionId,
            @RequestBody AgentSessionSnapshot snapshot) {
        Long ownerUserId = attachmentService.authenticate(token, executionId);
        sessionStreamService.publishSessionSnapshot(ownerUserId, executionId, snapshot);
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
