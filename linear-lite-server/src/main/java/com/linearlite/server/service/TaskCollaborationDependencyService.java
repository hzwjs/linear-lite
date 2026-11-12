package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.DependencyChange;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.entity.TaskExecutionOperation;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import com.linearlite.server.mapper.TaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 关系变更由持有领取权的 Agent 提交，项目协调事务串行化输入失效。 */
@Service
public class TaskCollaborationDependencyService {
    private static final String KIND = "dependency_change";
    private final TaskExecutionAccessService access;
    private final TaskCollaborationInputService inputs;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionOperationMapper operationMapper;
    private final TaskExecutionAttemptMapper attempts;
    private final TaskMapper tasks;
    private final ObjectMapper json;

    public TaskCollaborationDependencyService(TaskExecutionAccessService access, TaskCollaborationInputService inputs,
            TaskExecutionOperationService operations, TaskExecutionOperationMapper operationMapper,
            TaskExecutionAttemptMapper attempts, TaskMapper tasks, ObjectMapper json) {
        this.access = access;
        this.inputs = inputs;
        this.operations = operations;
        this.operationMapper = operationMapper;
        this.attempts = attempts;
        this.tasks = tasks;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode change(Long userId, CollaborationBatchRequest batch, Long taskId,
                           CollaborationAttemptCommand command, DependencyChange change) {
        validate(change);
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = json.createObjectNode().put("taskId", taskId)
                .put("batchId", owned.context().batchId()).put("bindingId", owned.context().bindingId())
                .put("attemptId", command.attemptId()).put("inputRevision", command.inputRevision())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId());
        request.set("change", json.valueToTree(change));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken(), command.claimToken());
        CollaborationCredentialPolicy.requireSafe(command.operationId(), batch.batchToken(), command.claimToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), KIND, request);
        if (replay != null) return replay;
        var check = access.checkWritable(owned, command);
        boolean retract = !check.accepted() && canRetract(owned, command, change);
        ObjectNode response = json.createObjectNode().put("accepted", check.accepted() || retract).put("changed", false);
        if (!check.accepted() && !retract) {
            response.put("reason", check.reason());
        } else if ("REQUEST_GOAL".equals(change.action())) {
            access.requestStop(owned.attempt(), "等待业务目标授权");
            response.put("outcome", "GOAL_AUTHORIZATION_REQUIRED").put("stopRequested", true)
                    .put("missing", change.missing()).put("goal", change.goal()).put("impact", change.impact())
                    .put("guidance", "新增业务目标经授权后进入新批次");
            appendFact(owned, command, change, response);
        } else {
            Task prerequisite = tasks.selectById(change.prerequisiteTaskId());
            if (prerequisite == null || !Objects.equals(prerequisite.getProjectId(), owned.context().projectId()))
                throw new ResourceNotFoundException("项目任务不存在");
            if ("ADD".equals(change.action()) && !owned.context().taskIds().contains(prerequisite.getId())
                    && inputs.currentTrace(prerequisite, owned.context().bindingId()) == null
                    && !inputs.hasExplanatoryDelivery(prerequisite, owned.context().bindingId())) {
                response.put("outcome", "MISSING_OUTSIDE_BATCH").put("missing", change.missing())
                        .put("goal", prerequisite.getTitle()).put("impact", "所需成果缺失，当前任务可能受阻")
                        .put("guidance", "当前授权目标内由 Agent 在本任务解决；新增业务目标经授权后进入新批次")
                        .put("stopRequested", false);
            } else {
                boolean changed = inputs.changeDependency(owned.task(), prerequisite.getId(),
                        "ADD".equals(change.action()), userId);
                response.put("changed", changed).put("outcome", "RECORDED").put("stopRequested", changed && !retract)
                        .put("retracted", retract)
                        .put("inputRevision", owned.task().getInputRevision() + (changed ? 1 : 0));
            }
            appendFact(owned, command, change, response);
        }
        operations.save(owned.context().projectId(), userId, command.operationId(), KIND,
                command.attemptId(), request, response);
        return response;
    }

    /** 调用方校验项目可见性后投影操作事实，不返回授权材料。 */
    @Transactional(readOnly = true)
    public List<DependencyFact> listFacts(Long projectId, Long taskId) {
        return operationMapper.selectDependencyFacts(projectId, taskId).stream().map(this::fact).toList();
    }

    /** 新目标等待用户授权期间，原批次不能重复领取同一任务。 */
    @Transactional(readOnly = true)
    public String goalAuthorizationBlock(Long projectId, Long taskId, String batchId, Long inputRevision) {
        return listFacts(projectId, taskId).stream()
                .filter(item -> item.accepted() && Objects.equals(item.batchId(), batchId)
                        && Objects.equals(item.sourceInputRevision(), inputRevision)
                        && "GOAL_AUTHORIZATION_REQUIRED".equals(item.outcome()))
                .map(item -> "新增业务目标等待授权：" + item.change().goal())
                .findFirst().orElse(null);
    }

    /** 停止确认后的领取凭据只可撤销该尝试建立且仍存在的关系。 */
    private boolean canRetract(TaskExecutionAccessService.OwnedAttempt owned, CollaborationAttemptCommand command,
                               DependencyChange change) {
        TaskExecutionAttempt attempt = owned.attempt();
        if (!"REMOVE".equals(change.action()) || attempt.getStopConfirmedAt() == null
                || attempt.getStopRequestedAt() == null || !Objects.equals(command.inputRevision(), owned.task().getInputRevision()))
            return false;
        List<TaskExecutionAttempt> history = attempts.selectByTaskId(owned.task().getId());
        if (history.isEmpty() || history.stream().anyMatch(item -> item.getStopConfirmedAt() == null)
                || !Objects.equals(history.get(history.size() - 1).getId(), attempt.getId())) return false;
        List<DependencyFact> facts = listFacts(owned.context().projectId(), owned.task().getId()).stream()
                .filter(fact -> fact.accepted() && fact.changed()
                        && Objects.equals(fact.change().prerequisiteTaskId(), change.prerequisiteTaskId())
                        && ("ADD".equals(fact.change().action()) || "REMOVE".equals(fact.change().action())))
                .toList();
        if (facts.isEmpty()) return false;
        DependencyFact latest = facts.get(facts.size() - 1);
        return "ADD".equals(latest.change().action()) && Objects.equals(latest.attemptId(), attempt.getId())
                && Objects.equals(latest.resultInputRevision(), owned.task().getInputRevision())
                && inputs.hasDependency(owned.task().getId(), change.prerequisiteTaskId());
    }

    private DependencyFact fact(TaskExecutionOperation operation) {
        try {
            JsonNode request = json.readTree(operation.getRequestJson());
            JsonNode response = json.readTree(operation.getResponseJson());
            return new DependencyFact(operation.getOperationId(), operation.getAttemptId(), operation.getUserId(),
                    request.get("batchId").textValue(), request.get("inputRevision").longValue(),
                    operation.getCreatedAt(), json.treeToValue(request.get("change"), DependencyChange.class),
                    response.path("accepted").asBoolean(), response.path("changed").asBoolean(),
                    response.path("outcome").isTextual() ? response.path("outcome").textValue() : null,
                    response.path("inputRevision").isIntegralNumber() ? response.path("inputRevision").longValue() : null);
        } catch (JsonProcessingException e) { throw new IllegalStateException("协作关系事实解析失败", e); }
    }

    private void appendFact(TaskExecutionAccessService.OwnedAttempt owned, CollaborationAttemptCommand command,
                            DependencyChange change, JsonNode response) {
        JsonNode previous = owned.attempt().getCheckpoints();
        if (previous == null || !previous.isArray()) throw new IllegalStateException("检查点数据无效");
        ObjectNode fact = json.createObjectNode().put("operationId", command.operationId())
                .put("agentInstanceId", command.agentInstanceId()).put("occurredAt", LocalDateTime.now().toString())
                .put("kind", KIND);
        fact.set("change", json.valueToTree(change));
        fact.set("result", response.deepCopy());
        try {
            if (attempts.appendCheckpoint(command.attemptId(), previous.size(), json.writeValueAsString(fact)) != 1)
                throw new IllegalStateException("关系事实保存失败");
        } catch (JsonProcessingException e) { throw new IllegalStateException("关系事实序列化失败", e); }
    }

    private void validate(DependencyChange change) {
        if (change == null || !("ADD".equals(change.action()) || "REMOVE".equals(change.action())
                || "REQUEST_GOAL".equals(change.action()))) throw new IllegalArgumentException("协作关系操作无效");
        if ("REQUEST_GOAL".equals(change.action())) {
            if (change.prerequisiteTaskId() != null) throw new IllegalArgumentException("业务目标请求不能指定任务");
            requireText(change.goal());
            requireText(change.impact());
        } else if (change.prerequisiteTaskId() == null || change.prerequisiteTaskId() <= 0) {
            throw new IllegalArgumentException("协作关系来源无效");
        }
        requireText(change.missing());
        requireText(change.reason());
        requireText(change.evidence());
    }

    private void requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 2048)
            throw new IllegalArgumentException("协作关系事实不完整");
    }

    public record DependencyFact(String operationId, Long attemptId, Long actorUserId, String batchId, Long sourceInputRevision, LocalDateTime occurredAt,
                                 DependencyChange change, boolean accepted, boolean changed, String outcome,
                                 Long resultInputRevision) { }
}
