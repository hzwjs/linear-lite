package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.PrerequisiteSourceObservation;
import com.linearlite.server.dto.AttemptPrerequisiteReviews;
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
import java.util.Map;
import java.util.Objects;

/** 项目行锁协调任务领取，执行期间不持数据库锁。 */
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
    private final CollaborationContextService contexts;
    private final TaskCollaborationDependencyService dependencies;
    private final TaskPrerequisiteReviewService reviews;

    public TaskCollaborationClaimService(CollaborationBatchAuthorizationService batches,
            TaskCollaborationInputService inputs, TaskExecutionAccessService access,
            TaskExecutionClaimCredentialService credentials, TaskExecutionOperationService operations,
            TaskExecutionAttemptMapper attempts, TaskMapper tasks, TaskStatusService statuses, ObjectMapper json,
            CollaborationContextService contexts, TaskCollaborationDependencyService dependencies, TaskPrerequisiteReviewService reviews) {
        this.batches = batches;
        this.inputs = inputs;
        this.access = access;
        this.credentials = credentials;
        this.operations = operations;
        this.attempts = attempts;
        this.tasks = tasks;
        this.statuses = statuses;
        this.json = json;
        this.contexts = contexts;
        this.dependencies = dependencies;
        this.reviews = reviews;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Observation observe(Long userId, CollaborationBatchRequest batch, List<PrerequisiteSourceObservation> sourceObservations) {
        var context = authorizeBatch(userId, batch);
        List<PrerequisiteSourceObservation> observations = validateObservations(sourceObservations);
        List<TaskExecutionAttempt> pending = pending(context.projectId());
        List<TaskView> views = new ArrayList<>();
        for (Long id : context.taskIds()) {
            Task task = tasks.selectById(id);
            if (task == null || !Objects.equals(task.getProjectId(), context.projectId())) {
                views.add(new TaskView(id, null, null, null, null, null,
                        "BLOCKED", "任务不存在或不属于项目", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
                continue;
            }
            Evaluation result = evaluate(task, context.bindingId(), context.batchId(), observations, pending);
            List<TaskExecutionAttempt> taskHistory = attempts.selectByTaskId(id);
            List<TaskExecutionAttemptResponse> taskAttempts = taskHistory.stream().map(TaskExecutionAttemptResponse::from).toList();
            // 环境只投影采用事实；缺宿主当前内容核验时不声明本地材料有效。
            List<AttemptContext> contextFacts = taskHistory.stream()
                    .map(a -> new AttemptContext(a.getId(), contexts.project(a, context.projectId(), Map.of()))).toList();
            views.add(new TaskView(task.getId(), task.getTaskKey(), task.getTitle(), task.getDescription(),
                    task.getStatus(), task.getInputRevision(), result.state(), result.reason(),
                    result.prerequisites(), taskAttempts, contextFacts, dependencies.listFacts(context.projectId(), id),
                    taskHistory.stream().map(a -> new AttemptPrerequisiteReviews(a.getId(), reviews.project(a))).toList(),
                    TaskPrerequisiteReviewService.projectStatuses(task, context.bindingId(), result.prerequisites(), taskHistory, sourceObservations)));
        }
        return new Observation(overall(views), List.copyOf(views));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ClaimResult claim(Long userId, CollaborationBatchRequest batch, Long taskId, Long inputRevision,
                             String hostInstanceId, String agentInstanceId, String operationId,
                             List<PrerequisiteSourceObservation> sourceObservations) {
        validateInstance(hostInstanceId, agentInstanceId);
        if (taskId == null || inputRevision == null) throw new IllegalArgumentException("领取参数不完整");
        var context = batches.authorizeTask(batch.batchToken(), userId, batch.bindingId(), batch.deviceId(),
                batch.projectDirectory(), taskId);
        CollaborationCredentialPolicy.requireSafe(hostInstanceId, batch.batchToken());
        if (!Objects.equals(context.hostInstanceId(), hostInstanceId))
            throw new com.linearlite.server.exception.ForbiddenOperationException("宿主执行归属不匹配");
        List<PrerequisiteSourceObservation> observations = validateObservations(sourceObservations);
        ObjectNode request = json.createObjectNode().put("batchId", context.batchId()).put("bindingId", context.bindingId())
                .put("taskId", taskId).put("inputRevision", inputRevision).put("hostInstanceId", hostInstanceId)
                .put("agentInstanceId", agentInstanceId);
        request.set("sourceObservations", json.valueToTree(observations));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken());
        CollaborationCredentialPolicy.requireSafe(operationId, batch.batchToken());
        JsonNode replay = operations.findReplay(context.projectId(), userId, operationId, "claim", request);
        String token = credentials.issue(context.batchId(), context.bindingId(), taskId,
                hostInstanceId, agentInstanceId, operationId);
        if (replay != null) return claimResult(replay, token);

        if (!batches.hasAttemptBudget(context)) {
            ObjectNode response = json.createObjectNode().put("accepted", false).put("reason", "批次尝试预算耗尽");
            operations.save(context.projectId(), userId, operationId, "claim", null, request, response);
            return claimResult(response, token);
        }
        if (!batches.hasExecutionSlot(context)) {
            ObjectNode response = json.createObjectNode().put("accepted", false).put("reason", "批次执行槽位已满");
            operations.save(context.projectId(), userId, operationId, "claim", null, request, response);
            return claimResult(response, token);
        }

        List<TaskExecutionAttempt> pending = pending(context.projectId());
        Task task = tasks.selectByIdForUpdate(taskId);
        if (task == null || !Objects.equals(task.getProjectId(), context.projectId()))
            throw new IllegalArgumentException("任务不存在或不属于项目");
        Evaluation evaluation = evaluate(task, context.bindingId(), context.batchId(), observations, pending);
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
        attempt.setStatus("running");
        // DATETIME 的秒精度与响应保持同一到期事实。
        attempt.setLeaseUntil(CollaborationLeaseTime.bounded(context.runLeaseUntil(), context.deadlineAt()));
        attempt.setCheckpoints(json.createArrayNode());
        if (attempts.insert(attempt) != 1 || attempt.getId() == null)
            throw new IllegalStateException("创建执行尝试失败");
        statuses.updateState(taskId, "in_progress", task.getProgressPercent() == null ? 0 : task.getProgressPercent(),
                userId, LocalDateTime.now());
        ObjectNode response = json.createObjectNode().put("accepted", true).put("attemptId", attempt.getId())
                .put("inputRevision", attempt.getInputRevision()).put("leaseUntil", CollaborationLeaseTime.toInstant(attempt.getLeaseUntil()).toString());
        response.set("prerequisites", json.valueToTree(evaluation.prerequisites()));
        operations.save(context.projectId(), userId, operationId, "claim", attempt.getId(), request, response);
        return claimResult(response, token);
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

    private Evaluation evaluate(Task task, Long bindingId, String batchId, List<PrerequisiteSourceObservation> sourceObservations,
                                List<TaskExecutionAttempt> pending) {
        List<TaskExecutionAttempt> held = pending.stream().filter(a -> Objects.equals(a.getTaskId(), task.getId())).toList();
        var input = inputs.evaluateInputs(task, task.getInputRevision(), bindingId, batchId);
        if (held.stream().anyMatch(a -> a.getStopRequestedAt() != null || !"running".equals(a.getStatus())))
            return new Evaluation("STOPPING_WAIT", "任务等待停止确认", input.prerequisites());
        if (!held.isEmpty()) return new Evaluation("TASK_WAIT", "任务正在执行", input.prerequisites());
        if (inputs.splitParent(task)) return "done".equalsIgnoreCase(task.getStatus())
                ? new Evaluation("COMPLETE", null, input.prerequisites())
                : new Evaluation("DEPENDENCY_WAIT", "等待子任务完成", input.prerequisites());
        if ("done".equalsIgnoreCase(task.getStatus())) {
            boolean delivered = attempts.selectByTaskId(task.getId()).stream().anyMatch(a ->
                    Objects.equals(a.getBindingId(), bindingId) && Objects.equals(a.getInputRevision(), task.getInputRevision())
                    && "succeeded".equals(a.getStatus()) && a.getStopConfirmedAt() != null
                    && a.getResultEvidence() != null && !a.getResultEvidence().isNull());
            return new Evaluation(delivered ? "COMPLETE" : "BLOCKED", delivered ? null : "任务缺少同绑定有效成果", input.prerequisites());
        }
        String goalBlock = dependencies.goalAuthorizationBlock(task.getProjectId(), task.getId(), batchId, task.getInputRevision());
        if (goalBlock != null) return new Evaluation("GOAL_AUTHORIZATION_WAIT", goalBlock, input.prerequisites());
        if (!"todo".equalsIgnoreCase(task.getStatus()))
            return new Evaluation("BLOCKED", "任务状态不允许领取", input.prerequisites());
        if (!input.ready() && !TaskCollaborationInputService.TRACE_PENDING.equals(input.reason()))
            return new Evaluation("DEPENDENCY_WAIT", input.reason(), input.prerequisites());
        var selection = verifiedPrerequisites(batchId, task, input.prerequisites(), sourceObservations);
        if (selection.blockedReason() != null)
            return new Evaluation("PREREQUISITE_REVIEW_WAIT", selection.blockedReason(), input.prerequisites());
        var verified = selection.sources();
        if (verified == null)
            return new Evaluation("DEPENDENCY_WAIT", TaskCollaborationInputService.TRACE_PENDING, input.prerequisites());
        String blocked = reviews.blockingReason(batchId, task, verified, sourceObservations);
        if (blocked != null) return new Evaluation("PREREQUISITE_REVIEW_WAIT", blocked, verified);
        return new Evaluation("READY", null, verified);
    }

    private Selection verifiedPrerequisites(String batchId, Task task,
            List<TaskCollaborationInputService.PrerequisiteResult> prerequisites,
            List<PrerequisiteSourceObservation> observations) {
        List<TaskCollaborationInputService.PrerequisiteResult> verified = new ArrayList<>();
        for (var source : prerequisites) {
            if (source.traceCandidates().isEmpty()) { verified.add(source); continue; }
            TaskCollaborationInputService.PrerequisiteResult selected = null;
            String blockedReason = null;
            for (var candidate : source.traceCandidates()) {
                JsonNode expected = candidate.evidence().path("artifactObservations");
                var current = observations.stream().filter(value -> Objects.equals(value.sourceAttemptId(), candidate.attemptId())
                        && Objects.equals(value.sourceInputRevision(), candidate.inputRevision())).findFirst().orElse(null);
                if (current == null) continue;
                ObjectNode evidence = json.createObjectNode();
                var paths = evidence.putArray("artifactRefs");
                var facts = evidence.putArray("artifactObservations");
                ObjectNode operationsByPath = evidence.putObject("traceOperationIds");
                for (JsonNode fact : expected) {
                    boolean match = current.observations().stream().anyMatch(value ->
                            Objects.equals(value.path(), fact.path("path").asText())
                            && "PRESENT".equals(value.state())
                            && Objects.equals(value.contentVersion(), fact.path("contentVersion").asText()));
                    if (match) {
                        String path = fact.path("path").asText();
                        paths.add(path);
                        facts.add(fact.deepCopy());
                        operationsByPath.put(path, candidate.evidence().path("traceOperationIds").path(path).asText());
                    }
                }
                if (!facts.isEmpty()) {
                    var candidateResult = new TaskCollaborationInputService.PrerequisiteResult(source.taskId(), candidate.attemptId(), candidate.inputRevision(), evidence, List.of());
                    List<TaskCollaborationInputService.PrerequisiteResult> proposed = new ArrayList<>(verified);
                    proposed.add(candidateResult);
                    String reason = reviews.blockingReason(batchId, task, proposed, observations);
                    if (reason != null) { blockedReason = reason; continue; }
                    selected = candidateResult;
                    break;
                }
            }
            if (selected == null) return new Selection(null, blockedReason);
            verified.add(selected);
        }
        return new Selection(List.copyOf(verified), null);
    }

    private record Selection(List<TaskCollaborationInputService.PrerequisiteResult> sources, String blockedReason) { }

    private String overall(List<TaskView> views) {
        if (views.isEmpty()) return "EMPTY";
        if (views.stream().allMatch(v -> "COMPLETE".equals(v.state()))) return "COMPLETE";
        if (views.stream().anyMatch(v -> "READY".equals(v.state()))) return "READY";
        if (views.stream().anyMatch(v -> "STOPPING_WAIT".equals(v.state()))) return "STOPPING_WAIT";
        if (views.stream().anyMatch(v -> "TASK_WAIT".equals(v.state()))) return "TASK_WAIT";
        if (views.stream().anyMatch(v -> "DEPENDENCY_WAIT".equals(v.state()))) return "DEPENDENCY_WAIT";
        if (views.stream().anyMatch(v -> "GOAL_AUTHORIZATION_WAIT".equals(v.state()))) return "GOAL_AUTHORIZATION_WAIT";
        if (views.stream().anyMatch(v -> "PREREQUISITE_REVIEW_WAIT".equals(v.state()))) return "PREREQUISITE_REVIEW_WAIT";
        return "BLOCKED";
    }

    private List<PrerequisiteSourceObservation> validateObservations(List<PrerequisiteSourceObservation> observations) {
        TaskPrerequisiteReviewService.validateSourceObservations(observations);
        return List.copyOf(observations);
    }

    private void validateInstance(String host, String agent) {
        if (host == null || host.isBlank() || host.length() > 128 || agent == null || agent.isBlank() || agent.length() > 128)
            throw new IllegalArgumentException("执行实例标识无效");
    }

    private ClaimResult claimResult(JsonNode result, String token) {
        if (!result.path("accepted").asBoolean()) return new ClaimResult(false, result.path("reason").asText(), null, null, null, null, List.of());
        if (!result.path("prerequisites").isArray()) throw new IllegalStateException("领取回执缺少固定来源");
        List<TaskCollaborationInputService.PrerequisiteResult> prerequisites = new ArrayList<>();
        for (JsonNode source : result.path("prerequisites")) {
            try { prerequisites.add(json.treeToValue(source, TaskCollaborationInputService.PrerequisiteResult.class)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("领取来源解析失败", e); }
        }
        return new ClaimResult(true, null, result.path("attemptId").longValue(), token,
                result.path("inputRevision").longValue(), Instant.parse(result.path("leaseUntil").textValue()), List.copyOf(prerequisites));
    }

    public record Observation(String state, List<TaskView> tasks) { }
    public record TaskView(Long taskId, String taskKey, String title, String description, String status, Long inputRevision,
                           String state, String reason,
                           List<TaskCollaborationInputService.PrerequisiteResult> prerequisites,
                           List<TaskExecutionAttemptResponse> attempts, List<AttemptContext> contextFacts,
                           List<TaskCollaborationDependencyService.DependencyFact> dependencyFacts,
                           List<AttemptPrerequisiteReviews> prerequisiteReviewFacts, List<com.linearlite.server.dto.PrerequisiteReviewStatus> prerequisiteReviewStatuses) { }
    public record AttemptContext(Long attemptId, List<CollaborationContextService.ContextStatus> refs) { }
    public record ClaimResult(boolean accepted, String reason, Long attemptId, String claimToken,
                              Long inputRevision, Instant leaseUntil, List<TaskCollaborationInputService.PrerequisiteResult> prerequisites) {
        @Override public String toString() { return "ClaimResult[accepted=" + accepted + ", reason=" + reason
                + ", attemptId=" + attemptId + ", claimToken=<redacted>, inputRevision=" + inputRevision
                + ", leaseUntil=" + leaseUntil + "]"; }
    }
    private record Evaluation(String state, String reason, List<TaskCollaborationInputService.PrerequisiteResult> prerequisites) { }
}
