package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskExecutionCheckpoint;
import com.linearlite.server.dto.CollaborationLeaseTime;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.time.BeijingTime;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 生命周期操作保留项目协调锁至尝试与回执提交。 */
@Service
public class TaskExecutionLifecycleService {
    private final TaskExecutionAccessService access;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionAttemptMapper attempts;
    private final ProjectMemberMapper members;
    private final TaskStatusService statuses;
    private final ObjectMapper json;

    public TaskExecutionLifecycleService(TaskExecutionAccessService access, TaskExecutionOperationService operations,
                                         TaskExecutionAttemptMapper attempts, ProjectMemberMapper members,
                                         TaskStatusService statuses, ObjectMapper json) {
        this.access = access;
        this.operations = operations;
        this.attempts = attempts;
        this.members = members;
        this.statuses = statuses;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result renew(Long userId, CollaborationBatchRequest batch, Long taskId, CollaborationAttemptCommand command) {
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = businessRequest(owned, command);
        requireSafe(request, command, batch.batchToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), "renew", request);
        if (replay != null) return result(replay);
        var check = access.checkWritable(owned, command);
        ObjectNode response = response(check.accepted(), check.reason());
        if (check.accepted()) {
            LocalDateTime leaseUntil = BeijingTime.now().plusMinutes(5).withNano(0);
            if (attempts.update(null, new UpdateWrapper<TaskExecutionAttempt>().eq("id", command.attemptId())
                    .set("lease_until", leaseUntil)) != 1) throw new IllegalStateException("续租失败");
            response.put("leaseUntil", CollaborationLeaseTime.toInstant(leaseUntil).toString());
        }
        save(owned.context().projectId(), userId, command, "renew", request, response);
        return result(response);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result checkpoint(Long userId, CollaborationBatchRequest batch, Long taskId,
                             CollaborationAttemptCommand command, TaskExecutionCheckpoint checkpoint) {
        if (command == null || checkpoint == null || !Objects.equals(command.operationId(), checkpoint.operationId())
                || !Objects.equals(command.agentInstanceId(), checkpoint.agentInstanceId())
                || checkpoint.occurredAt() == null || checkpoint.artifactRefs() == null) {
            throw new IllegalArgumentException("检查点参数不完整");
        }
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = businessRequest(owned, command);
        ObjectNode fact = checkpointJson(checkpoint);
        request.set("checkpoint", fact);
        requireSafe(request, command, batch.batchToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), "checkpoint", request);
        if (replay != null) return result(replay);
        var check = access.checkWritable(owned, command);
        ObjectNode response = response(check.accepted(), check.reason());
        if (check.accepted()) {
            JsonNode previous = owned.attempt().getCheckpoints();
            if (previous == null || !previous.isArray()) throw new IllegalStateException("检查点数据无效");
            try {
                if (attempts.appendCheckpoint(command.attemptId(), previous.size(), json.writeValueAsString(fact)) != 1)
                    throw new IllegalStateException("检查点追加失败");
            } catch (JsonProcessingException e) { throw new IllegalStateException("检查点序列化失败"); }
            response.put("sequence", previous.size() + 1);
            response.set("checkpoint", fact);
        }
        save(owned.context().projectId(), userId, command, "checkpoint", request, response);
        return result(response);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskExecutionAttemptResponse readRecovery(Long userId, Long projectId, Long bindingId, String deviceId,
                                                      Long taskId, CollaborationAttemptCommand command) {
        return TaskExecutionAttemptResponse.from(access.requireRecoveryAttempt(
                userId, projectId, bindingId, deviceId, taskId, command).attempt());
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result requestStop(Long userId, Long projectId, Long bindingId, String deviceId, Long taskId,
                              CollaborationAttemptCommand command, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 2048) throw new IllegalArgumentException("停止原因无效");
        var owned = access.requireRecoveryAttempt(userId, projectId, bindingId, deviceId, taskId, command);
        ObjectNode request = recoveryRequest(bindingId, deviceId, taskId, command).put("reason", reason);
        requireSafe(request, command, null);
        JsonNode replay = operations.findReplay(projectId, userId, command.operationId(), "stop_request", request);
        if (replay != null) return result(replay);
        TaskExecutionAttempt attempt = owned.attempt();
        ObjectNode response;
        if (attempt.getStopConfirmedAt() != null) {
            response = response(false, "执行尝试已确认停止");
        } else {
            access.requestStop(attempt, reason);
            response = response(true, null).put("stopRequestedAt", attempt.getStopRequestedAt().toString())
                    .put("stopReason", attempt.getStopReason());
        }
        save(projectId, userId, command, "stop_request", request, response);
        return result(response);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result confirmStop(Long userId, Long projectId, Long bindingId, String deviceId, Long taskId,
                              CollaborationAttemptCommand command, CollaborationStopConfirmation confirmation) {
        if (confirmation == null || !confirmation.stopped() || confirmation.detail() == null
                || confirmation.detail().isBlank() || confirmation.detail().length() > 2048) {
            throw new IllegalArgumentException("缺少宿主停止事实");
        }
        var owned = access.requireRecoveryAttempt(userId, projectId, bindingId, deviceId, taskId, command);
        ObjectNode request = recoveryRequest(bindingId, deviceId, taskId, command).put("detail", confirmation.detail());
        requireSafe(request, command, null);
        JsonNode replay = operations.findReplay(projectId, userId, command.operationId(), "stop_confirm", request);
        if (replay != null) return result(replay);
        TaskExecutionAttempt attempt = owned.attempt();
        if (attempt.getStopConfirmedAt() != null) {
            ObjectNode response = response(true, null).put("stopConfirmedAt", attempt.getStopConfirmedAt().toString());
            response.set("confirmation", attempt.getStopConfirmation());
            save(projectId, userId, command, "stop_confirm", request, response);
            return result(response);
        }
        if (attempt.getStopRequestedAt() == null && attempt.getLeaseUntil() != null
                && !attempt.getLeaseUntil().isAfter(BeijingTime.now())) access.requestStop(attempt, "执行租约过期");
        if (attempt.getStopRequestedAt() == null) {
            ObjectNode response = response(false, "执行尝试缺少停止请求");
            save(projectId, userId, command, "stop_confirm", request, response);
            return result(response);
        }
        LocalDateTime now = LocalDateTime.now();
        ObjectNode fact = json.createObjectNode().put("stopped", true).put("detail", confirmation.detail())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId())
                .put("confirmedAt", now.toString());
        TaskExecutionAttempt patch = new TaskExecutionAttempt();
        patch.setId(attempt.getId());
        patch.setStatus("failed".equals(attempt.getStatus()) ? "failed" : "stopped");
        patch.setStopConfirmedAt(now);
        patch.setStopConfirmation(fact);
        patch.setFinishedAt(now);
        if (attempts.updateById(patch) != 1) throw new IllegalStateException("停止确认保存失败");

        Task task = owned.task();
        String taskStatus = task.getStatus();
        if (!isTerminal(taskStatus) && Boolean.TRUE.equals(task.getAgentExecutionEnabled())) {
            Long count = members.selectCount(new LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, projectId).eq(ProjectMember::getUserId, userId));
            if (count == null) throw new IllegalStateException("项目成员查询失败");
            if (count > 0) {
                statuses.updateState(taskId, "todo", 0, userId, now);
                taskStatus = "todo";
            }
        }
        ObjectNode response = response(true, null).put("stopConfirmedAt", now.toString()).put("taskStatus", taskStatus);
        response.set("confirmation", fact);
        save(projectId, userId, command, "stop_confirm", request, response);
        return result(response);
    }

    private void requireSafe(JsonNode request, CollaborationAttemptCommand command, String batchToken) {
        CollaborationCredentialPolicy.requireSafe(request, batchToken, command.claimToken());
        CollaborationCredentialPolicy.requireSafe(command.operationId(), batchToken, command.claimToken());
    }

    private ObjectNode businessRequest(TaskExecutionAccessService.OwnedAttempt owned, CollaborationAttemptCommand command) {
        return json.createObjectNode().put("batchId", owned.context().batchId()).put("bindingId", owned.context().bindingId())
                .put("attemptId", command.attemptId()).put("inputRevision", command.inputRevision())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId());
    }

    private boolean isTerminal(String status) {
        return "done".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)
                || "duplicate".equalsIgnoreCase(status);
    }

    private ObjectNode recoveryRequest(Long bindingId, String deviceId, Long taskId, CollaborationAttemptCommand command) {
        return json.createObjectNode().put("bindingId", bindingId).put("deviceId", deviceId).put("taskId", taskId)
                .put("attemptId", command.attemptId()).put("hostInstanceId", command.hostInstanceId())
                .put("agentInstanceId", command.agentInstanceId());
    }

    private ObjectNode checkpointJson(TaskExecutionCheckpoint checkpoint) {
        ObjectNode fact = json.createObjectNode().put("operationId", checkpoint.operationId())
                .put("agentInstanceId", checkpoint.agentInstanceId()).put("occurredAt", checkpoint.occurredAt().toString());
        if (checkpoint.finding() != null) fact.put("finding", checkpoint.finding());
        if (checkpoint.blockedReason() != null) fact.put("blockedReason", checkpoint.blockedReason());
        fact.set("artifactRefs", json.valueToTree(checkpoint.artifactRefs()));
        return fact;
    }

    private ObjectNode response(boolean accepted, String reason) {
        ObjectNode response = json.createObjectNode().put("accepted", accepted);
        if (reason != null) response.put("reason", reason);
        return response;
    }

    private void save(Long projectId, Long userId, CollaborationAttemptCommand command, String kind,
                      JsonNode request, JsonNode response) {
        operations.save(projectId, userId, command.operationId(), kind, command.attemptId(), request, response);
    }

    private Result result(JsonNode response) {
        return new Result(response.path("accepted").asBoolean(), response.has("reason") ? response.path("reason").asText() : null,
                response.deepCopy());
    }

    public record Result(boolean accepted, String reason, JsonNode data) { }
}
