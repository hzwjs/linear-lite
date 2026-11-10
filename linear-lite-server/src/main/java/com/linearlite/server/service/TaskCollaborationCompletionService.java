package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskDeliveryEvidence;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 项目锁内提交交付事实，并经任务状态服务完成协作任务。 */
@Service
public class TaskCollaborationCompletionService {
    private final TaskExecutionAccessService access;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionAttemptMapper attempts;
    private final TaskStatusService statuses;
    private final TaskHierarchyCompletionService hierarchy;
    private final ObjectMapper json;

    public TaskCollaborationCompletionService(TaskExecutionAccessService access,
            TaskExecutionOperationService operations, TaskExecutionAttemptMapper attempts,
            TaskStatusService statuses, TaskHierarchyCompletionService hierarchy, ObjectMapper json) {
        this.access = access;
        this.operations = operations;
        this.attempts = attempts;
        this.statuses = statuses;
        this.hierarchy = hierarchy;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result complete(Long userId, CollaborationBatchRequest batch, Long taskId,
                           CollaborationAttemptCommand command, TaskDeliveryEvidence evidence,
                           CollaborationStopConfirmation confirmation) {
        validateEvidence(evidence, confirmation);
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = json.createObjectNode()
                .put("batchId", owned.context().batchId()).put("bindingId", owned.context().bindingId())
                .put("taskId", taskId).put("attemptId", command.attemptId())
                .put("inputRevision", command.inputRevision()).put("hostInstanceId", command.hostInstanceId())
                .put("agentInstanceId", command.agentInstanceId());
        request.set("evidence", json.valueToTree(evidence));
        request.set("stopConfirmation", json.valueToTree(confirmation));
        ObjectNode checkedPayload = json.createObjectNode().put("operationId", command.operationId());
        checkedPayload.set("request", request);
        CollaborationCredentialPolicy.requireSafe(checkedPayload, batch.batchToken(), command.claimToken());

        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), "complete", request);
        if (replay != null) return result(replay);
        var check = access.checkWritable(owned, command);
        if (!check.accepted()) {
            ObjectNode response = json.createObjectNode().put("accepted", false).put("reason", check.reason());
            operations.save(owned.context().projectId(), userId, command.operationId(), "complete",
                    command.attemptId(), request, response);
            return result(response);
        }
        requireWithinHeldScopes(evidence.changedPaths(), owned.attempt().getHeldWriteScopes());

        LocalDateTime now = LocalDateTime.now();
        ObjectNode delivery = json.valueToTree(evidence);
        delivery.put("inputRevision", owned.attempt().getInputRevision());
        delivery.set("prerequisiteVersions", owned.attempt().getPrerequisiteVersions().deepCopy());
        delivery.set("publicContextRefs", owned.task().getPublicContextRefs().deepCopy());
        ObjectNode stop = json.createObjectNode().put("stopped", true).put("detail", confirmation.detail())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId())
                .put("confirmedAt", now.toString());
        TaskExecutionAttempt patch = new TaskExecutionAttempt();
        patch.setId(command.attemptId());
        patch.setStatus("succeeded");
        patch.setResultEvidence(delivery);
        patch.setStopConfirmedAt(now);
        patch.setStopConfirmation(stop);
        patch.setFinishedAt(now);
        if (attempts.updateById(patch) != 1) throw new IllegalStateException("交付结果保存失败");

        statuses.updateState(taskId, "done", 100, userId, now);
        var ancestors = hierarchy.completeEligibleAncestors(taskId, userId, now);
        ObjectNode response = json.createObjectNode().put("accepted", true).put("attemptId", command.attemptId())
                .put("taskId", taskId).put("status", "done").put("inputRevision", owned.attempt().getInputRevision())
                .put("stopConfirmedAt", now.toString());
        response.set("evidence", delivery);
        response.set("ancestorChanges", json.valueToTree(ancestors));
        operations.save(owned.context().projectId(), userId, command.operationId(), "complete",
                command.attemptId(), request, response);
        return result(response);
    }

    private void validateEvidence(TaskDeliveryEvidence evidence, CollaborationStopConfirmation confirmation) {
        if (evidence == null || evidence.conditions() == null || evidence.conditions().isEmpty()
                || evidence.changedPaths() == null || evidence.artifactRefs() == null || evidence.artifactRefs().isEmpty()
                || evidence.projectCheckpoint() == null || evidence.projectCheckpoint().isBlank()) {
            throw new IllegalArgumentException("交付证据缺少完成条件、产物或项目检查点");
        }
        for (TaskDeliveryEvidence.CompletionCondition condition : evidence.conditions()) {
            if (condition == null || condition.condition() == null || condition.condition().isBlank()
                    || condition.result() == null || condition.result().isBlank()
                    || condition.evidenceRefs() == null || condition.evidenceRefs().isEmpty()
                    || condition.evidenceRefs().stream().anyMatch(ref -> ref == null || ref.isBlank())) {
                throw new IllegalArgumentException("完成条件缺少结果或证据引用");
            }
        }
        if (evidence.artifactRefs().stream().anyMatch(ref -> ref == null || ref.isBlank()))
            throw new IllegalArgumentException("产物引用无效");
        CollaborationPathScopeService.validate(evidence.changedPaths());
        if (confirmation == null || !confirmation.stopped() || confirmation.detail() == null
                || confirmation.detail().isBlank() || confirmation.detail().length() > 2048) {
            throw new IllegalArgumentException("缺少宿主停止事实");
        }
    }

    private void requireWithinHeldScopes(List<String> changedPaths, List<String> heldScopes) {
        if (heldScopes == null) throw new IllegalStateException("执行尝试缺少写入范围");
        for (String path : changedPaths) {
            String changed = canonicalKey(path);
            if (heldScopes.stream().map(this::canonicalKey)
                    .noneMatch(scope -> changed.equals(scope) || changed.startsWith(scope + "/"))) {
                throw new IllegalArgumentException("交付修改位置超出执行持有范围");
            }
        }
    }

    private String canonicalKey(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private Result result(JsonNode response) {
        return new Result(response.path("accepted").asBoolean(),
                response.has("reason") ? response.path("reason").asText() : null, response.deepCopy());
    }

    public record Result(boolean accepted, String reason, JsonNode data) { }
}
