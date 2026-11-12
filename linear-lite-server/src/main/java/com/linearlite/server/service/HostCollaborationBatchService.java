package com.linearlite.server.service;

import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.HostDeviceAuthorizationMapper;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Host OAuth 管理批次归属；原批次签名负责停止恢复。 */
@Service
public class HostCollaborationBatchService {
    private static final Set<String> FINISH_REASONS =
            Set.of("completed", "budget_exhausted", "host_stopped", "needs_decision");
    private final CollaborationBatchMapper batches;
    private final HostDeviceAuthorizationMapper hosts;
    private final ProjectMapper projects;
    private final ProjectDeviceBindingMapper bindings;
    private final TaskMapper tasks;
    private final TaskExecutionAttemptMapper attempts;
    private final ProjectAccessGuard access;
    private final CollaborationBatchControlService control;
    private final CollaborationBatchAuthorizationService authorization;
    private final Clock clock;

    @Autowired
    public HostCollaborationBatchService(CollaborationBatchMapper batches, HostDeviceAuthorizationMapper hosts,
            ProjectMapper projects, ProjectDeviceBindingMapper bindings, TaskMapper tasks,
            TaskExecutionAttemptMapper attempts, ProjectAccessGuard access, CollaborationBatchControlService control,
            CollaborationBatchAuthorizationService authorization) {
        this(batches, hosts, projects, bindings, tasks, attempts, access, control, authorization, Clock.systemUTC());
    }

    HostCollaborationBatchService(CollaborationBatchMapper batches, HostDeviceAuthorizationMapper hosts,
            ProjectMapper projects, ProjectDeviceBindingMapper bindings, TaskMapper tasks,
            TaskExecutionAttemptMapper attempts, ProjectAccessGuard access, CollaborationBatchControlService control,
            CollaborationBatchAuthorizationService authorization, Clock clock) {
        this.batches = batches; this.hosts = hosts; this.projects = projects; this.bindings = bindings;
        this.tasks = tasks; this.attempts = attempts; this.access = access; this.control = control;
        this.authorization = authorization; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Pending pending(Long userId, Long grantId, String deviceId) {
        requireIdentity(userId, grantId, deviceId);
        List<Context> contexts = batches.selectPendingForHost(userId, grantId).stream()
                .map(batch -> pendingContext(batch, userId, grantId, deviceId))
                .filter(Objects::nonNull).toList();
        return new Pending(contexts);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Context context(Long userId, Long grantId, String deviceId, Long projectId, String batchId) {
        requireIdentity(userId, grantId, deviceId);
        HostDeviceAuthorization grant = hosts.selectOwnedForUpdate(userId, grantId);
        if (grant == null || grant.getRevokedAt() != null || !Objects.equals(grant.getDeviceId(), deviceId)) throw denied();
        if (projectId == null || projectId <= 0 || batchId == null || batchId.isBlank()) throw denied();
        if (projects.lockProject(projectId) == null) throw denied();
        access.requireMember(projectId, userId);
        ProjectDeviceBinding binding = bindings.selectForUpdate(userId, projectId, deviceId);
        CollaborationBatch batch = batches.selectForUpdate(batchId);
        if (!matches(batch, binding, userId, grantId, deviceId, projectId)) throw denied();
        return context(batch, binding);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CollaborationBatchAuthorizationService.PreparedAcceptance prepareAccept(
            Long userId, Long grantId, String deviceId, Long projectId, String batchId, String hostInstanceId) {
        if (hostInstanceId == null || !hostInstanceId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("宿主实例标识无效");
        Context current = context(userId, grantId, deviceId, projectId, batchId);
        if (!"queued".equals(current.lifecycle()) || !"RUN".equals(current.desiredIntent()))
            throw new ConflictOperationException("批次不在待接受状态");
        return authorization.prepareAcceptance(current, userId, grantId, hostInstanceId);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Finished finishAccept(String recoveryToken, CollaborationStopConfirmation confirmation) {
        requireStopConfirmation(confirmation, recoveryToken);
        CollaborationBatch batch = authorization.lockPreparedAcceptance(recoveryToken);
        if (batches.countAttempts(batch.getBatchId()) != 0)
            throw new ConflictOperationException("批次已有执行记录，请使用运行凭据恢复");
        if ("ended".equals(batch.getLifecycle())) return finished(batch);
        // 同一项目事务同时封住迟到的接受请求和已经提交的接受。
        int updated = "queued".equals(batch.getLifecycle())
                ? batches.finishQueuedAcceptance(batch.getBatchId(), batch.getRunEpoch(), now())
                : batches.finish(batch.getBatchId(), batch.getHostInstanceId(), batch.getRunEpoch(), "host_stopped", now());
        if (updated != 1) throw new ConflictOperationException("批次归属或结束状态已变化");
        return finished(batches.selectForUpdate(batch.getBatchId()));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CollaborationBatchAuthorizationService.IssuedBatch accept(Long userId, Long grantId, String deviceId,
                                                                       Long projectId, String batchId,
                                                                       String hostInstanceId, String recoveryToken) {
        requireIdentity(userId, grantId, deviceId);
        // 接受方法持有 grant → project → binding → batch 锁至外层事务结束。
        control.acceptQueued(userId, projectId, batchId, grantId, hostInstanceId);
        CollaborationBatch batch = batches.selectForUpdate(batchId);
        HostDeviceAuthorization grant = hosts.selectOwnedForUpdate(userId, grantId);
        ProjectDeviceBinding binding = bindings.selectForUpdate(userId, projectId, deviceId);
        if (!matches(batch, binding, userId, grantId, deviceId, projectId)
                || grant == null || !Objects.equals(grant.getDeviceId(), deviceId)
                || !Objects.equals(batch.getHostInstanceId(), hostInstanceId)) throw denied();
        authorization.verifyPreparedAcceptance(recoveryToken, batch, deviceId, hostInstanceId);
        return authorization.issueAccepted(batch, binding, grant);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Lease renew(Long userId, Long grantId, String deviceId, String batchToken) {
        var context = authorization.authorizeHostControl(batchToken, userId, grantId, deviceId);
        CollaborationBatch batch = batches.selectForUpdate(context.batchId());
        if ("RUN".equals(batch.getDesiredIntent())) {
            LocalDateTime now = now();
            LocalDateTime until = now.plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
            if (batch.getDeadlineAt().isBefore(until)) until = batch.getDeadlineAt();
            if (batches.renewRunLease(batch.getBatchId(), batch.getHostInstanceId(), batch.getRunEpoch(), now, until) != 1)
                throw new ConflictOperationException("批次运行租约已失效");
            batch.setRunLeaseUntil(until);
        }
        return new Lease(batch.getBatchId(), batch.getDesiredIntent(), instant(batch.getRunLeaseUntil()),
                instant(batch.getDeadlineAt()));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Finished finish(String batchToken, String reason, CollaborationStopConfirmation confirmation) {
        if (reason == null || !FINISH_REASONS.contains(reason))
            throw new IllegalArgumentException("批次结束原因无效");
        requireStopConfirmation(confirmation, batchToken);
        Long userId = authorization.recoveryUserId(batchToken);
        var context = authorization.recoveryContext(batchToken, userId);
        CollaborationBatch batch = batches.selectForUpdate(context.batchId());
        if ("ended".equals(batch.getLifecycle())) return finished(batch);
        if (batches.countUnconfirmedAttempts(batch.getBatchId()) != 0)
            throw new ConflictOperationException("批次存在未确认停止的执行");
        if ("RUN".equals(batch.getDesiredIntent())) {
            if ("completed".equals(reason)) requireCompleted(context);
            if ("budget_exhausted".equals(reason) && batch.getDeadlineAt().isAfter(now())
                    && batches.countAttempts(batch.getBatchId()) < batch.getMaxAttempts())
                throw new ConflictOperationException("批次预算尚未耗尽");
        }
        if (batches.finish(batch.getBatchId(), context.hostInstanceId(), context.runEpoch(), reason, now()) != 1)
            throw new ConflictOperationException("批次归属或结束状态已变化");
        return finished(batches.selectForUpdate(batch.getBatchId()));
    }

    private void requireStopConfirmation(CollaborationStopConfirmation confirmation, String credential) {
        if (confirmation == null || !confirmation.stopped() || confirmation.detail() == null
                || confirmation.detail().isBlank() || confirmation.detail().length() > 2048)
            throw new IllegalArgumentException("批次停止事实无效");
        CollaborationCredentialPolicy.requireSafe(confirmation.detail(), credential);
    }

    private void requireCompleted(CollaborationBatchAuthorizationService.BatchContext context) {
        for (Long taskId : context.taskIds()) {
            Task task = tasks.selectByIdForUpdate(taskId);
            if (task == null || !Objects.equals(task.getProjectId(), context.projectId())
                    || !"done".equalsIgnoreCase(task.getStatus())) throw new ConflictOperationException("批次任务尚未完成");
            var history = attempts.selectByTaskId(taskId);
            Long children = tasks.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task>()
                    .eq(Task::getParentId, taskId));
            if (children != null && children > 0 && TaskSplitFacts.confirmed(history)) continue;
            boolean delivered = history.stream().anyMatch(attempt ->
                    Objects.equals(attempt.getBindingId(), context.bindingId())
                            && Objects.equals(attempt.getInputRevision(), task.getInputRevision())
                            && "succeeded".equals(attempt.getStatus()) && attempt.getStopConfirmedAt() != null
                            && attempt.getResultEvidence() != null && !attempt.getResultEvidence().isNull());
            if (!delivered) throw new ConflictOperationException("批次任务缺少同绑定有效成果");
        }
    }

    private Context pendingContext(CollaborationBatch batch, Long userId, Long grantId, String deviceId) {
        ProjectDeviceBinding binding = bindings.selectById(batch.getBindingId());
        return matches(batch, binding, userId, grantId, deviceId, batch.getProjectId()) ? context(batch, binding) : null;
    }

    private Context context(CollaborationBatch batch, ProjectDeviceBinding binding) {
        return new Context(batch.getBatchId(), batch.getProjectId(), batch.getBindingId(), batch.getBindingRevision(),
                binding.getDeviceId(), binding.getProjectDirectory(), batches.selectTaskIds(batch.getBatchId()),
                batch.getMaxSlots(), batch.getMaxDurationMs(), batch.getMaxAttempts(), batches.countAttempts(batch.getBatchId()), batch.getLifecycle(),
                batch.getDesiredIntent(), batch.getHostInstanceId(), batch.getRunEpoch(), instant(batch.getRunLeaseUntil()),
                instant(batch.getDeadlineAt()));
    }

    private boolean matches(CollaborationBatch batch, ProjectDeviceBinding binding, Long userId, Long grantId,
                            String deviceId, Long projectId) {
        return batch != null && binding != null && binding.getDeletedAt() == null
                && Objects.equals(batch.getUserId(), userId) && Objects.equals(batch.getProjectId(), projectId)
                && Objects.equals(batch.getHostAuthorizationId(), grantId)
                && Objects.equals(batch.getBindingId(), binding.getId())
                && Objects.equals(batch.getBindingRevision(), binding.getRevision())
                && Objects.equals(binding.getUserId(), userId) && Objects.equals(binding.getProjectId(), projectId)
                && Objects.equals(binding.getDeviceId(), deviceId);
    }

    private Finished finished(CollaborationBatch batch) {
        return new Finished(batch.getBatchId(), batch.getLifecycle(), batch.getDesiredIntent(),
                batch.getEndReason(), instant(batch.getEndedAt()));
    }
    private void requireIdentity(Long userId, Long grantId, String deviceId) {
        if (userId == null || userId <= 0 || grantId == null || grantId <= 0 || deviceId == null || deviceId.isBlank())
            throw denied();
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private ForbiddenOperationException denied() { return new ForbiddenOperationException("宿主批次归属不匹配"); }

    public record Pending(List<Context> items) { }
    public record Context(String batchId, Long projectId, Long bindingId, Long bindingRevision, String deviceId,
                          String projectDirectory, List<Long> taskIds, Integer maxSlots, Long maxDurationMs,
                          Integer maxAttempts, long attemptsUsed, String lifecycle, String desiredIntent, String hostInstanceId,
                          Long runEpoch, Instant runLeaseUntil, Instant deadlineAt) { }
    public record Lease(String batchId, String desiredIntent, Instant runLeaseUntil, Instant deadlineAt) { }
    public record Finished(String batchId, String lifecycle, String desiredIntent, String endReason, Instant endedAt) { }
}
