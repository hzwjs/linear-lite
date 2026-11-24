package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/** The single write path for status, progress, and completion timestamp. */
@Service
public class TaskStatusService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "canceled", "duplicate");

    private final TaskMapper taskMapper;
    private final TaskActivityService taskActivityService;

    public TaskStatusService(TaskMapper taskMapper, TaskActivityService taskActivityService) {
        this.taskMapper = taskMapper;
        this.taskActivityService = taskActivityService;
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

    private static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.toLowerCase());
    }
}
