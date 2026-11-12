package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.time.BeijingTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** 所有业务写入共用归属与当前执行资格判断。 */
@Service
public class TaskExecutionAccessService {
    private final CollaborationBatchAuthorizationService batches;
    private final TaskExecutionClaimCredentialService credentials;
    private final TaskMapper tasks;
    private final TaskExecutionAttemptMapper attempts;
    private final TaskCollaborationInputService inputs;

    public TaskExecutionAccessService(CollaborationBatchAuthorizationService batches,
                                      TaskExecutionClaimCredentialService credentials,
                                      TaskMapper tasks, TaskExecutionAttemptMapper attempts,
                                      TaskCollaborationInputService inputs) {
        this.batches = batches;
        this.credentials = credentials;
        this.tasks = tasks;
        this.attempts = attempts;
        this.inputs = inputs;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OwnedAttempt requireOwnedAttempt(Long userId, CollaborationBatchRequest batch, Long taskId,
                                             CollaborationAttemptCommand command) {
        if (batch == null || command == null || command.attemptId() == null || taskId == null
                || command.claimToken() == null || command.hostInstanceId() == null || command.agentInstanceId() == null) {
            throw new IllegalArgumentException("执行归属参数不完整");
        }
        var context = batches.authorizeTask(batch.batchToken(), userId, batch.bindingId(), batch.deviceId(),
                batch.projectDirectory(), taskId);
        Task task = tasks.selectByIdForUpdate(taskId);
        TaskExecutionAttempt attempt = attempts.selectByIdForUpdate(command.attemptId());
        if (task == null || attempt == null || !Objects.equals(task.getProjectId(), context.projectId())
                || !Objects.equals(attempt.getTaskId(), taskId) || !Objects.equals(attempt.getBindingId(), context.bindingId())
                || !Objects.equals(attempt.getBatchId(), context.batchId())
                || !Objects.equals(context.hostInstanceId(), command.hostInstanceId())
                || !Objects.equals(attempt.getHostInstanceId(), command.hostInstanceId())
                || !Objects.equals(attempt.getAgentInstanceId(), command.agentInstanceId())
                || !credentials.matches(command.claimToken(), attempt.getClaimTokenHash())) {
            throw new ForbiddenOperationException("执行尝试归属不匹配");
        }
        return new OwnedAttempt(context, task, attempt);
    }

    /** 停止恢复只认证尝试归属，不授予过期批次的业务写入权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public RecoveryAttempt requireRecoveryAttempt(Long userId, String batchToken, Long projectId, Long bindingId, String deviceId,
                                                   Long taskId, CollaborationAttemptCommand command) {
        if (userId == null || projectId == null || bindingId == null || taskId == null || deviceId == null
                || deviceId.isBlank() || command == null || command.attemptId() == null
                || command.claimToken() == null || command.hostInstanceId() == null || command.agentInstanceId() == null) {
            throw new IllegalArgumentException("停止恢复参数不完整");
        }
        var context = batches.recoveryContext(batchToken, userId);
        if (!Objects.equals(context.projectId(), projectId) || !Objects.equals(context.bindingId(), bindingId)
                || !Objects.equals(context.deviceId(), deviceId) || !context.taskIds().contains(taskId)
                || !Objects.equals(context.hostInstanceId(), command.hostInstanceId()))
            throw new ForbiddenOperationException("执行尝试归属不匹配");
        Task task = tasks.selectByIdForUpdate(taskId);
        TaskExecutionAttempt attempt = attempts.selectByIdForUpdate(command.attemptId());
        if (task == null || !Objects.equals(task.getProjectId(), projectId)
                || attempt == null || !Objects.equals(attempt.getTaskId(), taskId)
                || !Objects.equals(attempt.getBindingId(), bindingId)
                || !Objects.equals(attempt.getBatchId(), context.batchId())
                || !Objects.equals(attempt.getHostInstanceId(), command.hostInstanceId())
                || !Objects.equals(attempt.getAgentInstanceId(), command.agentInstanceId())
                || !credentials.matches(command.claimToken(), attempt.getClaimTokenHash())) {
            throw new ForbiddenOperationException("执行尝试归属不匹配");
        }
        return new RecoveryAttempt(context, task, attempt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WriteCheck checkWritable(OwnedAttempt owned, CollaborationAttemptCommand command) {
        TaskExecutionAttempt attempt = owned.attempt();
        Task task = owned.task();
        if (attempt.getStopConfirmedAt() != null || attempt.getStopRequestedAt() != null
                || !"running".equals(attempt.getStatus())) {
            return new WriteCheck(false, "执行尝试不再允许写入", attempt);
        }
        String reason = null;
        if (attempt.getLeaseUntil() == null || !attempt.getLeaseUntil().isAfter(BeijingTime.now())) {
            reason = "执行租约过期";
        } else if (!Objects.equals(task.getInputRevision(), attempt.getInputRevision())) {
            reason = "任务输入版本已变化";
        } else if (!"in_progress".equalsIgnoreCase(task.getStatus())) {
            reason = "任务状态不允许写入";
        }
        if (reason == null) {
            if (!inputs.matchesPrerequisiteSnapshot(task, attempt)) reason = "前置成果已变化";
        }
        if (reason == null && !Objects.equals(command.inputRevision(), attempt.getInputRevision()))
            return new WriteCheck(false, "请求输入版本不匹配", attempt);
        if (reason == null) return new WriteCheck(true, null, attempt);
        requestStop(attempt, reason);
        return new WriteCheck(false, reason, attempt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestStop(TaskExecutionAttempt attempt, String reason) {
        if (attempt.getStopConfirmedAt() == null && attempt.getStopRequestedAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            attempts.update(null, new UpdateWrapper<TaskExecutionAttempt>().eq("id", attempt.getId())
                    .isNull("stop_confirmed_at").isNull("stop_requested_at")
                    .set("status", "stop_requested").set("stop_requested_at", now).set("stop_reason", reason));
            attempt.setStatus("stop_requested");
            attempt.setStopRequestedAt(now);
            attempt.setStopReason(reason);
        }
    }

    public record OwnedAttempt(CollaborationBatchAuthorizationService.BatchContext context, Task task,
                               TaskExecutionAttempt attempt) { }
    public record RecoveryAttempt(CollaborationBatchAuthorizationService.BatchContext context, Task task, TaskExecutionAttempt attempt) { }
    public record WriteCheck(boolean accepted, String reason, TaskExecutionAttempt attempt) { }
}
