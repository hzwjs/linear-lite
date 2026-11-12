package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private static final String CLAIM_FENCE = "claim_recovery_fence";
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
            Long taskId, Long inputRevision, String hostInstanceId, String agentInstanceId, String claimOperationId,
            String operationId, CollaborationStopConfirmation confirmation) {
        var context = batches.recoveryContext(batchToken, userId);
        if (!Objects.equals(context.bindingId(), bindingId) || !Objects.equals(context.deviceId(), deviceId)
                || !context.taskIds().contains(taskId) || !Objects.equals(context.hostInstanceId(), hostInstanceId)
                || inputRevision == null || inputRevision <= 0
                || !validIdentifier(hostInstanceId) || !validIdentifier(agentInstanceId)
                || !validIdentifier(claimOperationId) || !validIdentifier(operationId)) throw denied();
        if (confirmation == null || !confirmation.stopped() || confirmation.detail() == null
                || confirmation.detail().isBlank() || confirmation.detail().length() > 2048)
            throw new IllegalArgumentException("执行停止事实无效");
        CollaborationCredentialPolicy.requireSafe(claimOperationId, batchToken);
        CollaborationCredentialPolicy.requireSafe(operationId, batchToken);
        CollaborationCredentialPolicy.requireSafe(hostInstanceId, batchToken);
        CollaborationCredentialPolicy.requireSafe(agentInstanceId, batchToken);
        if (confirmation != null) CollaborationCredentialPolicy.requireSafe(confirmation.detail(), batchToken);
        if (projects.lockProject(context.projectId()) == null) throw denied();
        TaskExecutionOperation receipt = operations.selectForUpdate(context.projectId(), userId, claimOperationId);
        if (receipt == null) {
            // 与领取持有同一个项目锁，原操作标识的栅栏阻止迟到的领取写入。
            ObjectNode request = identity(context.batchId(), bindingId, taskId, inputRevision, hostInstanceId, agentInstanceId);
            TaskExecutionOperation fence = new TaskExecutionOperation();
            fence.setProjectId(context.projectId());
            fence.setUserId(userId);
            fence.setOperationId(claimOperationId);
            fence.setKind(CLAIM_FENCE);
            fence.setRequestJson(write(request));
            fence.setResponseJson(write(json.createObjectNode().put("accepted", false).put("reason", "领取恢复封存")));
            if (operations.insert(fence) != 1) throw new IllegalStateException("领取恢复事实保存失败");
            return noAttempt("FENCED");
        }
        JsonNode request = read(receipt.getRequestJson());
        if (!matchesIdentity(request, context.batchId(), bindingId, taskId, inputRevision, hostInstanceId, agentInstanceId)) throw denied();
        JsonNode response = read(receipt.getResponseJson());
        if (CLAIM_FENCE.equals(receipt.getKind())) {
            if (receipt.getAttemptId() != null || !response.path("accepted").isBoolean()
                    || response.path("accepted").booleanValue()) throw denied();
            return noAttempt("FENCED");
        }
        if (!"claim".equals(receipt.getKind())) throw denied();
        if (!response.path("accepted").isBoolean()) throw denied();
        if (!response.path("accepted").booleanValue()) {
            if (receipt.getAttemptId() != null || response.hasNonNull("attemptId")) throw denied();
            return noAttempt("REJECTED");
        }
        if (!response.path("attemptId").isIntegralNumber() || !response.path("attemptId").canConvertToLong()
                || !Objects.equals(response.path("attemptId").longValue(), receipt.getAttemptId())
                || receipt.getAttemptId() <= 0) throw denied();
        String claimToken = credentials.issue(context.batchId(), bindingId, taskId, hostInstanceId, agentInstanceId, claimOperationId);
        var command = new CollaborationAttemptCommand(receipt.getAttemptId(), claimToken,
                request.path("inputRevision").longValue(), hostInstanceId, agentInstanceId, operationId);
        var owned = access.requireRecoveryAttempt(userId, batchToken, context.projectId(), bindingId, deviceId, taskId, command);
        if (!context.batchId().equals(owned.attempt().getBatchId())
                || !command.inputRevision().equals(owned.attempt().getInputRevision())) throw denied();
        access.requestStop(owned.attempt(), "领取确认恢复停止");
        var result = lifecycle.confirmStop(userId, batchToken, context.projectId(), bindingId, deviceId, taskId, command, confirmation);
        ObjectNode data = result.data().deepCopy();
        data.put("attemptId", receipt.getAttemptId()).put("claimOutcome", "CLAIMED");
        data.set("attempt", json.valueToTree(lifecycle.readRecovery(
                userId, batchToken, context.projectId(), bindingId, deviceId, taskId, command)));
        return new TaskExecutionLifecycleService.Result(result.accepted(), result.reason(), data);
    }

    private TaskExecutionLifecycleService.Result noAttempt(String outcome) {
        ObjectNode data = json.createObjectNode().put("accepted", true).put("claimOutcome", outcome);
        data.putNull("attemptId");
        return new TaskExecutionLifecycleService.Result(true, null, data);
    }

    private ObjectNode identity(String batchId, Long bindingId, Long taskId, Long inputRevision, String host, String agent) {
        return json.createObjectNode().put("batchId", batchId).put("bindingId", bindingId).put("taskId", taskId)
                .put("inputRevision", inputRevision).put("hostInstanceId", host).put("agentInstanceId", agent);
    }

    private boolean matchesIdentity(JsonNode request, String batchId, Long bindingId, Long taskId, Long inputRevision,
                                    String host, String agent) {
        return request.isObject() && Objects.equals(batchId, request.path("batchId").textValue())
                && request.path("bindingId").isIntegralNumber() && request.path("bindingId").canConvertToLong()
                && Objects.equals(bindingId, request.path("bindingId").longValue())
                && request.path("taskId").isIntegralNumber() && request.path("taskId").canConvertToLong()
                && Objects.equals(taskId, request.path("taskId").longValue())
                && request.path("inputRevision").isIntegralNumber() && request.path("inputRevision").canConvertToLong()
                && Objects.equals(inputRevision, request.path("inputRevision").longValue())
                && Objects.equals(host, request.path("hostInstanceId").textValue())
                && Objects.equals(agent, request.path("agentInstanceId").textValue());
    }

    private boolean validIdentifier(String value) { return value != null && !value.isBlank() && value.length() <= 128; }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ignored) { throw new IllegalStateException("领取恢复事实序列化失败"); }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception ignored) { throw denied(); }
    }
    private ForbiddenOperationException denied() { return new ForbiddenOperationException("原领取恢复证明无效，执行范围保持冻结"); }
}
