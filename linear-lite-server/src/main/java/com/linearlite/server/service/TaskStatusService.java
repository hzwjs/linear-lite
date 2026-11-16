package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskDependencyMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.Objects;

/** The single write path for status, progress, and completion timestamp. */
@Service
public class TaskStatusService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "canceled", "duplicate");

    private final TaskMapper taskMapper;
    private final TaskActivityService taskActivityService;
    private final TaskDependencyMapper taskDependencyMapper;
    private final TaskExecutionAttemptMapper taskExecutionAttemptMapper;

    public TaskStatusService(TaskMapper taskMapper, TaskActivityService taskActivityService,
                             TaskDependencyMapper taskDependencyMapper,
                             TaskExecutionAttemptMapper taskExecutionAttemptMapper) {
        this.taskMapper = taskMapper;
        this.taskActivityService = taskActivityService;
        this.taskDependencyMapper = taskDependencyMapper;
        this.taskExecutionAttemptMapper = taskExecutionAttemptMapper;
    }

    public Task updateState(Long taskId, String status, int progressPercent, Long actorUserId,
                            LocalDateTime occurredAt) {
        Task existing = taskMapper.selectByIdForUpdate(taskId);
        if (existing == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        String oldStatus = existing.getStatus();
        int oldProgress = existing.getProgressPercent() == null ? 0 : existing.getProgressPercent();
        boolean statusChanged = !status.equalsIgnoreCase(oldStatus);
        boolean progressChanged = progressPercent != oldProgress;
        if (!statusChanged && !progressChanged) {
            return existing;
        }

        LocalDateTime completedAt = existing.getCompletedAt();
        if (statusChanged) {
            completedAt = isTerminal(status) ? occurredAt : null;
        }
        taskMapper.update(null, new UpdateWrapper<Task>()
                .eq("id", taskId)
                .set("status", status)
                .set("progress_percent", progressPercent)
                .set("completed_at", completedAt));

        if (actorUserId != null) {
            if (statusChanged) {
                taskActivityService.recordFieldChangeAt(taskId, actorUserId, "status", oldStatus, status, occurredAt);
            }
            if (progressChanged) {
                taskActivityService.recordFieldChangeAt(
                        taskId, actorUserId, "progressPercent", String.valueOf(oldProgress),
                        String.valueOf(progressPercent), occurredAt);
            }
        }
        return taskMapper.selectById(taskId);
    }

    public boolean requiresDeliveryEvidence(Long taskId) {
        return taskDependencyMapper.hasCollaborationParticipation(taskId);
    }

    /** 协作任务完成所需的证据：当前交付，或已确认的拆分（由子任务汇总完成）。 */
    public boolean hasCompletionEvidence(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        return task != null && hasCompletionEvidence(task);
    }

    private boolean hasCompletionEvidence(Task task) {
        if (hasCurrentDelivery(task)) return true;
        Long children = taskMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task>()
                .eq(Task::getParentId, task.getId()));
        return children != null && children > 0 && TaskSplitFacts.confirmed(taskExecutionAttemptMapper.selectByTaskId(task.getId()));
    }

    private boolean hasCurrentDelivery(Task task) {
        return taskExecutionAttemptMapper.selectByTaskId(task.getId()).stream().anyMatch(attempt ->
                "succeeded".equals(attempt.getStatus()) && attempt.getStopConfirmedAt() != null
                        && attempt.getResultEvidence() != null && attempt.getResultEvidence().isObject()
                        && !attempt.getResultEvidence().isEmpty()
                        && Objects.equals(task.getInputRevision(), attempt.getInputRevision()));
    }

    private static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.toLowerCase());
    }
}
