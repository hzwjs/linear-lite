package com.linearlite.server.service;

import com.linearlite.server.dto.TaskImportRequest;
import com.linearlite.server.dto.TaskImportResponse;
import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Task;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 兼容门面：将历史 TaskService 能力路由到 Query / Command / Import 领域服务。
 */
@Service
public class TaskService {

    private static final Set<String> OPEN_STATUSES_FOR_PROGRESS_LINKAGE = Set.of(
            "backlog", "todo", "in_progress", "in_review");

    private final TaskQueryService taskQueryService;
    private final TaskCommandService taskCommandService;
    private final TaskImportService taskImportService;

    public TaskService(
            TaskQueryService taskQueryService,
            TaskCommandService taskCommandService,
            TaskImportService taskImportService) {
        this.taskQueryService = taskQueryService;
        this.taskCommandService = taskCommandService;
        this.taskImportService = taskImportService;
    }

    public List<Task> listByProjectId(Long projectId, Boolean topLevelOnly, Long parentId, Long userId) {
        return taskQueryService.listByProjectId(projectId, topLevelOnly, parentId, userId);
    }

    public Task create(Long projectId, Long creatorId, Long parentId, String title, String description,
                       String status, String priority, Long assigneeId, LocalDateTime dueDate,
                       LocalDateTime plannedStartDate, Integer progressPercent, List<TaskLabelItemRequest> labels) {
        return taskCommandService.create(projectId, creatorId, parentId, title, description, status, priority,
                assigneeId, dueDate, plannedStartDate, progressPercent, labels);
    }

    public TaskImportResponse importTasks(TaskImportRequest request, Long creatorId) {
        return taskImportService.importTasks(request, creatorId);
    }

    public Task update(String taskKey, UpdateTaskRequest request) {
        return update(taskKey, request, null);
    }

    public Task update(String taskKey, UpdateTaskRequest request, Long userId) {
        return taskCommandService.update(taskKey, request, userId);
    }

    public Task getByKeyOrThrow(String taskKey, Long userId) {
        return taskQueryService.getByKeyOrThrow(taskKey, userId);
    }

    public List<Task> listFavorites(Long userId) {
        return taskQueryService.listFavorites(userId);
    }

    public Task addFavorite(String taskKey, Long userId) {
        return taskCommandService.addFavorite(taskKey, userId);
    }

    public Task removeFavorite(String taskKey, Long userId) {
        return taskCommandService.removeFavorite(taskKey, userId);
    }

    private static boolean isOpenForProgressLinkage(String status) {
        return status != null && OPEN_STATUSES_FOR_PROGRESS_LINKAGE.contains(status.toLowerCase());
    }

    static ResolvedStatusProgress resolveStatusProgressLinkage(
            Task existing,
            boolean statusTouched,
            boolean progressTouched,
            String proposedStatus,
            int proposedProgress) {
        String st = proposedStatus;
        int pr = proposedProgress;

        if (statusTouched
                && existing.getStatus() != null
                && "done".equalsIgnoreCase(existing.getStatus())
                && isOpenForProgressLinkage(st)
                && pr >= 100) {
            pr = 99;
        }

        if (progressTouched
                && pr < 100
                && existing.getStatus() != null
                && "done".equalsIgnoreCase(existing.getStatus())
                && "done".equalsIgnoreCase(st)) {
            st = "in_progress";
        }

        if (pr == 100 && isOpenForProgressLinkage(st)) {
            st = "done";
        }
        if ("done".equalsIgnoreCase(st)) {
            pr = 100;
        }

        return new ResolvedStatusProgress(st, pr);
    }

    static final class ResolvedStatusProgress {
        final String status;
        final int progressPercent;

        ResolvedStatusProgress(String status, int progressPercent) {
            this.status = status;
            this.progressPercent = progressPercent;
        }
    }
}
