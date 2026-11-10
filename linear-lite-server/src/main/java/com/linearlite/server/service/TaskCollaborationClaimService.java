package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.dto.CollaborationLeaseTime;
import com.linearlite.server.time.BeijingTime;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 项目行锁协调领取及范围持有，执行期间不持数据库锁。 */
@Service
public class TaskCollaborationClaimService {
    private final CollaborationBatchAuthorizationService batches;
    private final TaskCollaborationInputService inputs;
    private final TaskExecutionAccessService access;
    private final TaskExecutionClaimCredentialService credentials;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionAttemptMapper attempts;
    private final TaskMapper tasks;
    private final TaskStatusService statuses;
    private final ObjectMapper json;

    public TaskCollaborationClaimService(CollaborationBatchAuthorizationService batches,
            TaskCollaborationInputService inputs, TaskExecutionAccessService access,
            TaskExecutionClaimCredentialService credentials, TaskExecutionOperationService operations,
            TaskExecutionAttemptMapper attempts, TaskMapper tasks, TaskStatusService statuses, ObjectMapper json) {
        this.batches = batches;
        this.inputs = inputs;
        this.access = access;
        this.credentials = credentials;
        this.operations = operations;
        this.attempts = attempts;
        this.tasks = tasks;
        this.statuses = statuses;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Observation observe(Long userId, CollaborationBatchRequest batch, List<AvailableResult> available) {
        var context = authorizeBatch(userId, batch);
        List<AvailableResult> availability = validateAvailable(available);
        List<TaskExecutionAttempt> pending = pending(context.projectId());
        List<TaskView> views = new ArrayList<>();
        for (Long id : context.taskIds()) {
            Task task = tasks.selectById(id);
            if (task == null || !Objects.equals(task.getProjectId(), context.projectId())) {
                views.add(new TaskView(id, null, null, null, null, null, null,
                        "BLOCKED", "任务不存在或不属于项目", List.of(), List.of()));
                continue;
            }
            Evaluation result = evaluate(task, context.bindingId(), availability, pending);
            List<TaskExecutionAttemptResponse> taskAttempts = attempts.selectByTaskId(id).stream()
                    .map(TaskExecutionAttemptResponse::from).toList();
            views.add(new TaskView(task.getId(), task.getTaskKey(), task.getTitle(), task.getDescription(),
                    task.getStatus(), task.getInputRevision(), task.getWriteScopes(), result.state(), result.reason(),
                    result.prerequisites(), taskAttempts));
        }
        List<ResourceOwner> resourceOwners = pending.stream()
                .filter(a -> Objects.equals(a.getBindingId(), context.bindingId()))
                .map(a -> new ResourceOwner(a.getId(), a.getTaskId(), a.getHostInstanceId(), a.getAgentInstanceId(),
                        a.getHeldWriteScopes(), a.getStatus(), CollaborationLeaseTime.toInstant(a.getLeaseUntil()), a.getStopRequestedAt())).toList();
        return new Observation(overall(views), List.copyOf(views), resourceOwners);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ClaimResult claim(Long userId, CollaborationBatchRequest batch, Long taskId, Long inputRevision,
                             String hostInstanceId, String agentInstanceId, String operationId,
                             List<AvailableResult> available) {
        validateInstance(hostInstanceId, agentInstanceId);
        if (taskId == null || inputRevision == null) throw new IllegalArgumentException("领取参数不完整");
        var context = batches.authorizeTask(batch.batchToken(), userId, batch.bindingId(), batch.deviceId(),
                batch.projectDirectory(), taskId);
        List<AvailableResult> availability = validateAvailable(available);
        ObjectNode request = json.createObjectNode().put("batchId", context.batchId()).put("bindingId", context.bindingId())
                .put("taskId", taskId).put("inputRevision", inputRevision).put("hostInstanceId", hostInstanceId)
                .put("agentInstanceId", agentInstanceId);
        request.set("available", json.valueToTree(availability));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken());
        CollaborationCredentialPolicy.requireSafe(operationId, batch.batchToken());
        JsonNode replay = operations.findReplay(context.projectId(), userId, operationId, "claim", request);
        String token = credentials.issue(context.batchId(), context.bindingId(), taskId,
                hostInstanceId, agentInstanceId, operationId);
        if (replay != null) return claimResult(replay, token);

        List<TaskExecutionAttempt> pending = pending(context.projectId());
        Task task = tasks.selectByIdForUpdate(taskId);
        if (task == null || !Objects.equals(task.getProjectId(), context.projectId()))
            throw new IllegalArgumentException("任务不存在或不属于项目");
        Evaluation evaluation = evaluate(task, context.bindingId(), availability, pending);
        if (!Objects.equals(inputRevision, task.getInputRevision()))
            evaluation = new Evaluation("BLOCKED", "任务输入版本已变化", List.of());
        if (!"READY".equals(evaluation.state())) {
            ObjectNode response = json.createObjectNode().put("accepted", false).put("reason", evaluation.reason());
            operations.save(context.projectId(), userId, operationId, "claim", null, request, response);
            return claimResult(response, token);
        }

        TaskExecutionAttempt attempt = new TaskExecutionAttempt();
        attempt.setTaskId(taskId);
        attempt.setBindingId(context.bindingId());
        attempt.setBatchId(context.batchId());
        attempt.setHostInstanceId(hostInstanceId);
        attempt.setAgentInstanceId(agentInstanceId);
        attempt.setClaimTokenHash(credentials.hash(token));
        attempt.setInputRevision(task.getInputRevision());
        attempt.setPrerequisiteVersions(json.valueToTree(evaluation.prerequisites()));
        attempt.setHeldWriteScopes(CollaborationPathScopeService.validate(task.getWriteScopes()));
        attempt.setStatus("running");
        // DATETIME 的秒精度与响应保持同一到期事实。
        attempt.setLeaseUntil(BeijingTime.now().plusMinutes(5).withNano(0));
        attempt.setCheckpoints(json.createArrayNode());
        if (attempts.insert(attempt) != 1 || attempt.getId() == null)
            throw new IllegalStateException("创建执行尝试失败");
        statuses.updateState(taskId, "in_progress", task.getProgressPercent() == null ? 0 : task.getProgressPercent(),
                userId, LocalDateTime.now());
        ObjectNode response = json.createObjectNode().put("accepted", true).put("attemptId", attempt.getId())
                .put("inputRevision", attempt.getInputRevision()).put("leaseUntil", CollaborationLeaseTime.toInstant(attempt.getLeaseUntil()).toString());
        operations.save(context.projectId(), userId, operationId, "claim", attempt.getId(), request, response);
        return claimResult(response, token);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ScopeResult expand(Long userId, CollaborationBatchRequest batch, Long taskId,
                              CollaborationAttemptCommand command, List<String> requestedScopes) {
        List<String> requested = CollaborationPathScopeService.validate(requestedScopes);
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = json.createObjectNode().put("batchId", owned.context().batchId())
                .put("attemptId", command.attemptId()).put("inputRevision", command.inputRevision())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId());
        request.set("requestedScopes", json.valueToTree(requested));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken(), command.claimToken());
        CollaborationCredentialPolicy.requireSafe(command.operationId(), batch.batchToken(), command.claimToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), "scope", request);
        if (replay != null) return scopeResult(replay);
        var check = access.checkWritable(owned, command);
        String reason = check.reason();
        if (check.accepted()) {
            List<TaskExecutionAttempt> pending = pending(owned.context().projectId());
            boolean conflict = pending.stream().anyMatch(other -> !Objects.equals(other.getId(), command.attemptId())
                    && Objects.equals(other.getBindingId(), owned.context().bindingId())
                    && CollaborationPathScopeService.overlaps(requested, other.getHeldWriteScopes()));
            if (conflict) {
                reason = "写入范围与其他执行冲突";
                access.requestStop(owned.attempt(), reason);
            } else {
                List<String> merged = new ArrayList<>(owned.attempt().getHeldWriteScopes());
                for (String scope : requested) {
                    String key = CollaborationPathScopeService.canonicalKey(scope);
                    if (merged.stream().noneMatch(held -> CollaborationPathScopeService.canonicalKey(held).equals(key)))
                        merged.add(scope);
                }
                CollaborationPathScopeService.validate(merged);
                if (!merged.equals(owned.attempt().getHeldWriteScopes())) {
                    TaskExecutionAttempt patch = new TaskExecutionAttempt();
                    patch.setId(owned.attempt().getId());
                    patch.setHeldWriteScopes(merged);
                    if (attempts.updateById(patch) != 1) throw new IllegalStateException("写入范围更新失败");
                    owned.attempt().setHeldWriteScopes(merged);
                }
            }
        }
        ObjectNode response = json.createObjectNode().put("accepted", reason == null);
        if (reason != null) response.put("reason", reason);
        response.set("heldWriteScopes", json.valueToTree(owned.attempt().getHeldWriteScopes()));
        operations.save(owned.context().projectId(), userId, command.operationId(), "scope", command.attemptId(), request, response);
        return scopeResult(response);
    }

    private CollaborationBatchAuthorizationService.BatchContext authorizeBatch(Long userId, CollaborationBatchRequest batch) {
        if (batch == null) throw new IllegalArgumentException("批次参数不能为空");
        return batches.observeBatch(batch.batchToken(), userId, batch.bindingId(), batch.deviceId(), batch.projectDirectory());
    }

    private List<TaskExecutionAttempt> pending(Long projectId) {
        List<TaskExecutionAttempt> result = attempts.selectUnconfirmedByProject(projectId);
        for (TaskExecutionAttempt attempt : result) {
            if (attempt.getStopRequestedAt() == null && attempt.getLeaseUntil() != null
                    && !attempt.getLeaseUntil().isAfter(BeijingTime.now()))
                access.requestStop(attempt, "执行租约过期");
        }
        return result;
    }

    private Evaluation evaluate(Task task, Long bindingId, List<AvailableResult> available,
                                List<TaskExecutionAttempt> pending) {
        List<TaskExecutionAttempt> held = pending.stream().filter(a -> Objects.equals(a.getTaskId(), task.getId())).toList();
        if (!held.isEmpty()) return new Evaluation("ACTIVE", "任务存在活动或冻结执行", List.of());
        if ("done".equalsIgnoreCase(task.getStatus())) {
            boolean delivered = attempts.selectByTaskId(task.getId()).stream().anyMatch(a ->
                    Objects.equals(a.getBindingId(), bindingId) && Objects.equals(a.getInputRevision(), task.getInputRevision())
                    && "succeeded".equals(a.getStatus()) && a.getStopConfirmedAt() != null
                    && a.getResultEvidence() != null && !a.getResultEvidence().isNull());
            return new Evaluation(delivered ? "COMPLETE" : "BLOCKED", delivered ? null : "任务缺少同绑定有效成果", List.of());
        }
        if (!Boolean.TRUE.equals(task.getAgentExecutionEnabled()))
            return new Evaluation("BLOCKED", "协作执行未授权", List.of());
        if (!"todo".equalsIgnoreCase(task.getStatus()))
            return new Evaluation("BLOCKED", "任务状态不允许领取", List.of());
        var input = inputs.evaluateInputs(task, task.getInputRevision(), bindingId);
        if (!input.ready()) return new Evaluation("DEPENDENCY_WAIT", input.reason(), input.prerequisites());
        for (var prerequisite : input.prerequisites()) {
            if (!available.contains(new AvailableResult(prerequisite.attemptId(), prerequisite.inputRevision())))
                return new Evaluation("DEPENDENCY_WAIT", "宿主缺少前置成果", input.prerequisites());
        }
        List<String> scopes = CollaborationPathScopeService.validate(task.getWriteScopes());
        if (pending.stream().anyMatch(a -> Objects.equals(a.getBindingId(), bindingId)
                && CollaborationPathScopeService.overlaps(scopes, a.getHeldWriteScopes())))
            return new Evaluation("RESOURCE_WAIT", "写入范围与其他执行冲突", input.prerequisites());
        return new Evaluation("READY", null, input.prerequisites());
    }

    private String overall(List<TaskView> views) {
        if (views.isEmpty()) return "EMPTY";
        if (views.stream().allMatch(v -> "COMPLETE".equals(v.state()))) return "COMPLETE";
        if (views.stream().anyMatch(v -> "READY".equals(v.state()))) return "READY";
        if (views.stream().anyMatch(v -> "ACTIVE".equals(v.state()))) return "ACTIVE";
        if (views.stream().anyMatch(v -> "RESOURCE_WAIT".equals(v.state()))) return "RESOURCE_WAIT";
        if (views.stream().anyMatch(v -> "DEPENDENCY_WAIT".equals(v.state()))) return "DEPENDENCY_WAIT";
        return "BLOCKED";
    }

    private List<AvailableResult> validateAvailable(List<AvailableResult> available) {
        if (available == null || available.stream().anyMatch(a -> a == null || a.attemptId() == null
                || a.inputRevision() == null || a.attemptId() <= 0 || a.inputRevision() <= 0))
            throw new IllegalArgumentException("前置成果可用声明无效");
        List<AvailableResult> sorted = available.stream().sorted(Comparator.comparing(AvailableResult::attemptId)
                .thenComparing(AvailableResult::inputRevision)).toList();
        if (sorted.stream().distinct().count() != sorted.size()) throw new IllegalArgumentException("前置成果可用声明重复");
        return sorted;
    }

    private void validateInstance(String host, String agent) {
        if (host == null || host.isBlank() || host.length() > 128 || agent == null || agent.isBlank() || agent.length() > 128)
            throw new IllegalArgumentException("执行实例标识无效");
    }

    private ClaimResult claimResult(JsonNode result, String token) {
        if (!result.path("accepted").asBoolean()) return new ClaimResult(false, result.path("reason").asText(), null, null, null, null);
        return new ClaimResult(true, null, result.path("attemptId").longValue(), token,
                result.path("inputRevision").longValue(), Instant.parse(result.path("leaseUntil").textValue()));
    }

    private ScopeResult scopeResult(JsonNode result) {
        List<String> scopes = new ArrayList<>();
        result.path("heldWriteScopes").forEach(node -> scopes.add(node.textValue()));
        return new ScopeResult(result.path("accepted").asBoolean(),
                result.has("reason") ? result.get("reason").asText() : null, List.copyOf(scopes));
    }

    public record AvailableResult(Long attemptId, Long inputRevision) { }
    public record Observation(String state, List<TaskView> tasks, List<ResourceOwner> resourceOwners) { }
    public record ResourceOwner(Long attemptId, Long taskId, String hostInstanceId, String agentInstanceId,
                                List<String> heldWriteScopes, String status, Instant leaseUntil,
                                LocalDateTime stopRequestedAt) { }
    public record TaskView(Long taskId, String taskKey, String title, String description, String status, Long inputRevision,
                           List<String> writeScopes, String state, String reason,
                           List<TaskCollaborationInputService.PrerequisiteResult> prerequisites,
                           List<TaskExecutionAttemptResponse> attempts) { }
    public record ClaimResult(boolean accepted, String reason, Long attemptId, String claimToken,
                              Long inputRevision, Instant leaseUntil) {
        @Override public String toString() { return "ClaimResult[accepted=" + accepted + ", reason=" + reason
                + ", attemptId=" + attemptId + ", claimToken=<redacted>, inputRevision=" + inputRevision
                + ", leaseUntil=" + leaseUntil + "]"; }
    }
    public record ScopeResult(boolean accepted, String reason, List<String> heldWriteScopes) { }
    private record Evaluation(String state, String reason, List<TaskCollaborationInputService.PrerequisiteResult> prerequisites) { }
}
