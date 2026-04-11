package com.linearlite.server.service;

import com.linearlite.server.dto.TaskImportRequest;
import com.linearlite.server.dto.TaskImportResponse;
import com.linearlite.server.dto.TaskImportRowRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TaskImportService {

    private static final int TASK_IMPORT_MAX_ROWS = 800;
    private static final int ASSIGNEE_DISPLAY_NAME_MAX_LEN = 128;
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "backlog", "todo", "in_progress", "in_review", "done", "canceled", "duplicate");
    private static final Set<String> ALLOWED_PRIORITIES = Set.of("low", "medium", "high", "urgent");
    private static final Set<String> OPEN_STATUSES_FOR_PROGRESS_LINKAGE = Set.of(
            "backlog", "todo", "in_progress", "in_review");

    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final TaskPermissionGuard taskPermissionGuard;
    private final TaskSequenceService taskSequenceService;
    private final TaskActivityService taskActivityService;

    public TaskImportService(
            ProjectMapper projectMapper,
            TaskMapper taskMapper,
            TaskPermissionGuard taskPermissionGuard,
            TaskSequenceService taskSequenceService,
            TaskActivityService taskActivityService) {
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.taskPermissionGuard = taskPermissionGuard;
        this.taskSequenceService = taskSequenceService;
        this.taskActivityService = taskActivityService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskImportResponse importTasks(TaskImportRequest request, Long creatorId) {
        if (request == null) {
            throw new IllegalArgumentException("导入请求不能为空");
        }
        if (request.getProjectId() == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        requireUserId(creatorId);

        Project project = projectMapper.selectById(request.getProjectId());
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + request.getProjectId());
        }
        taskPermissionGuard.requireProjectMember(request.getProjectId(), creatorId);

        List<TaskImportRowRequest> rows = request.getRows() == null ? List.of() : request.getRows();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("导入数据不能为空");
        }
        if (rows.size() > TASK_IMPORT_MAX_ROWS) {
            throw new IllegalArgumentException("单次导入最多支持 800 行");
        }

        Map<String, TaskImportRowRequest> rowByImportId = new HashMap<>();
        for (TaskImportRowRequest row : rows) {
            validateImportRow(row);
            String importId = row.getImportId().trim();
            if (rowByImportId.putIfAbsent(importId, row) != null) {
                throw new IllegalArgumentException("Import ID must be unique within the file: " + importId);
            }
        }

        for (TaskImportRowRequest row : rows) {
            String parentImportId = trimToNull(row.getParentImportId());
            String importId = trimToNull(row.getImportId());
            if (parentImportId == null) {
                continue;
            }
            if (parentImportId.equals(importId)) {
                throw new IllegalArgumentException("Parent Import ID cannot reference the same row: " + importId);
            }
            if (!rowByImportId.containsKey(parentImportId)) {
                throw new IllegalArgumentException("Parent Import ID must reference another row in the same file: " + parentImportId);
            }
        }
        validateNoImportCycles(rowByImportId);

        long startTaskNumber = taskSequenceService.reserveTaskNumbers(
                request.getProjectId(), project.getIdentifier(), rows.size());
        Map<String, Task> createdTaskByImportId = new HashMap<>();
        List<String> createdTaskKeys = new ArrayList<>();

        for (int index = 0; index < rows.size(); index++) {
            TaskImportRowRequest row = rows.get(index);
            Task task = new Task();
            task.setTaskKey(project.getIdentifier() + "-" + (startTaskNumber + index));
            task.setTitle(row.getTitle().trim());
            task.setDescription(trimToNull(row.getDescription()));
            task.setStatus(defaultStatus(row.getStatus()));
            task.setPriority(defaultPriority(row.getPriority()));
            task.setProjectId(request.getProjectId());
            task.setCreatorId(creatorId);
            Long assigneeId = row.getAssigneeId();
            task.setAssigneeId(assigneeId);
            if (assigneeId != null) {
                task.setAssigneeDisplayName(null);
            } else {
                task.setAssigneeDisplayName(truncateAssigneeDisplayName(trimToNull(row.getAssigneeDisplayName())));
            }
            task.setDueDate(row.getDueDate());
            task.setPlannedStartDate(row.getPlannedStartDate());
            task.setProgressPercent(clampProgressPercent(row.getProgressPercent()));
            normalizeProgressStatusForNewTask(task);
            if (isTerminalStatus(task.getStatus())) {
                task.setCompletedAt(LocalDateTime.now());
            }
            taskMapper.insert(task);
            taskActivityService.recordAction(task.getId(), creatorId, "created");
            createdTaskByImportId.put(row.getImportId().trim(), task);
            createdTaskKeys.add(task.getTaskKey());
        }

        for (TaskImportRowRequest row : rows) {
            String parentImportId = trimToNull(row.getParentImportId());
            if (parentImportId == null) {
                continue;
            }
            Task child = createdTaskByImportId.get(row.getImportId().trim());
            Task parent = createdTaskByImportId.get(parentImportId);
            Task update = new Task();
            update.setId(child.getId());
            update.setParentId(parent.getId());
            taskMapper.updateById(update);
        }

        TaskImportResponse response = new TaskImportResponse();
        response.setCreatedCount(rows.size());
        response.setParentCount((int) rows.stream().filter(row -> trimToNull(row.getParentImportId()) == null).count());
        response.setSubtaskCount((int) rows.stream().filter(row -> trimToNull(row.getParentImportId()) != null).count());
        response.setTaskKeys(createdTaskKeys);
        return response;
    }

    private void validateImportRow(TaskImportRowRequest row) {
        if (row == null) {
            throw new IllegalArgumentException("导入行不能为空");
        }
        String importId = trimToNull(row.getImportId());
        if (importId == null) {
            throw new IllegalArgumentException("Import ID is required.");
        }
        String title = trimToNull(row.getTitle());
        if (title == null) {
            throw new IllegalArgumentException("Title is required.");
        }
        String status = trimToNull(row.getStatus());
        if (status != null && !ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
        String priority = trimToNull(row.getPriority());
        if (priority != null && !ALLOWED_PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("Invalid priority: " + priority);
        }
        if (row.getProgressPercent() != null) {
            int p = row.getProgressPercent();
            if (p < 0 || p > 100) {
                throw new IllegalArgumentException("Progress must be between 0 and 100: " + p);
            }
        }
    }

    private void validateNoImportCycles(Map<String, TaskImportRowRequest> rowByImportId) {
        for (TaskImportRowRequest row : rowByImportId.values()) {
            Set<String> visited = new HashSet<>();
            String current = trimToNull(row.getParentImportId());
            while (current != null) {
                if (!visited.add(current)) {
                    throw new IllegalArgumentException("Parent Import ID creates a cycle: " + current);
                }
                TaskImportRowRequest parent = rowByImportId.get(current);
                current = parent == null ? null : trimToNull(parent.getParentImportId());
            }
        }
    }

    private String defaultStatus(String status) {
        String value = trimToNull(status);
        return value != null ? value : "backlog";
    }

    private String defaultPriority(String priority) {
        String value = trimToNull(priority);
        return value != null ? value : "medium";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncateAssigneeDisplayName(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= ASSIGNEE_DISPLAY_NAME_MAX_LEN) {
            return value;
        }
        return value.substring(0, ASSIGNEE_DISPLAY_NAME_MAX_LEN);
    }

    private static int clampProgressPercent(Integer value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, value));
    }

    private static boolean isTerminalStatus(String status) {
        return status != null && Set.of("done", "canceled").contains(status.toLowerCase());
    }

    private static boolean isOpenForProgressLinkage(String status) {
        return status != null && OPEN_STATUSES_FOR_PROGRESS_LINKAGE.contains(status.toLowerCase());
    }

    private static void normalizeProgressStatusForNewTask(Task task) {
        if (task == null || task.getStatus() == null) {
            return;
        }
        String st = task.getStatus().trim();
        int pr = task.getProgressPercent() != null ? task.getProgressPercent() : 0;
        if (pr == 100 && isOpenForProgressLinkage(st)) {
            st = "done";
        }
        if ("done".equalsIgnoreCase(st)) {
            pr = 100;
        }
        task.setStatus(st);
        task.setProgressPercent(pr);
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
    }
}
