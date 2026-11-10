package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.entity.TaskExecutionOperation;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 领取提交但凭据尚未持久化的恢复窗口；只处理原尝试的停止。 */
@Service
public class UnacknowledgedClaimRecoveryService {
    private final CollaborationBatchAuthorizationService batches;
    private final TaskExecutionClaimCredentialService credentials;
    private final TaskExecutionAccessService access;
    private final TaskExecutionLifecycleService lifecycle;
    private final TaskExecutionOperationMapper operations;
    private final ProjectMapper projects;
    private final ObjectMapper json;

    public UnacknowledgedClaimRecoveryService(CollaborationBatchAuthorizationService batches,
            TaskExecutionClaimCredentialService credentials, TaskExecutionAccessService access,
            TaskExecutionLifecycleService lifecycle, TaskExecutionOperationMapper operations,
            ProjectMapper projects, ObjectMapper json) {
        this.batches = batches; this.credentials = credentials; this.access = access;
        this.lifecycle = lifecycle; this.operations = operations; this.projects = projects; this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskExecutionLifecycleService.Result recover(Long userId, String batchToken, Long bindingId, String deviceId,
            Long taskId, String hostInstanceId, String agentInstanceId, String claimOperationId,
            String operationId, CollaborationStopConfirmation confirmation) {
        var context = batches.recoveryContext(batchToken, userId);
        if (!Objects.equals(context.bindingId(), bindingId) || !Objects.equals(context.deviceId(), deviceId)
                || !context.taskIds().contains(taskId) || hostInstanceId == null || agentInstanceId == null
                || claimOperationId == null || operationId == null || claimOperationId.isBlank()
                || claimOperationId.length() > 128) throw denied();
        CollaborationCredentialPolicy.requireSafe(claimOperationId, batchToken);
        CollaborationCredentialPolicy.requireSafe(operationId, batchToken);
        CollaborationCredentialPolicy.requireSafe(hostInstanceId, batchToken);
        CollaborationCredentialPolicy.requireSafe(agentInstanceId, batchToken);
        if (confirmation != null) CollaborationCredentialPolicy.requireSafe(confirmation.detail(), batchToken);
        if (projects.lockProject(context.projectId()) == null) throw denied();
        TaskExecutionOperation receipt = operations.selectForUpdate(context.projectId(), userId, claimOperationId);
        if (receipt == null || !"claim".equals(receipt.getKind())) throw denied();
        JsonNode request = read(receipt.getRequestJson()), response = read(receipt.getResponseJson());
        if (!response.path("accepted").asBoolean() || !response.path("attemptId").canConvertToLong()
                || !Objects.equals(response.path("attemptId").longValue(), receipt.getAttemptId())
                || !Objects.equals(context.batchId(), request.path("batchId").textValue())
                || !Objects.equals(bindingId, request.path("bindingId").longValue())
                || !Objects.equals(taskId, request.path("taskId").longValue())
                || !Objects.equals(hostInstanceId, request.path("hostInstanceId").textValue())
                || !Objects.equals(agentInstanceId, request.path("agentInstanceId").textValue())
                || !request.path("inputRevision").canConvertToLong()) throw denied();
        String claimToken = credentials.issue(context.batchId(), bindingId, taskId, hostInstanceId, agentInstanceId, claimOperationId);
        var command = new CollaborationAttemptCommand(receipt.getAttemptId(), claimToken,
                request.path("inputRevision").longValue(), hostInstanceId, agentInstanceId, operationId);
        var owned = access.requireRecoveryAttempt(userId, context.projectId(), bindingId, deviceId, taskId, command);
        if (!context.batchId().equals(owned.attempt().getBatchId())
                || !command.inputRevision().equals(owned.attempt().getInputRevision())) throw denied();
        access.requestStop(owned.attempt(), "领取确认恢复停止");
        var result = lifecycle.confirmStop(userId, context.projectId(), bindingId, deviceId, taskId, command, confirmation);
        var data = result.data().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("attemptId", receipt.getAttemptId());
        ((com.fasterxml.jackson.databind.node.ObjectNode) data).set("attempt", json.valueToTree(lifecycle.readRecovery(
                userId, context.projectId(), bindingId, deviceId, taskId, command)));
        return new TaskExecutionLifecycleService.Result(result.accepted(), result.reason(), data);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception ignored) { throw denied(); }
    }
    private ForbiddenOperationException denied() { return new ForbiddenOperationException("原领取恢复证明无效，执行范围保持冻结"); }
}
