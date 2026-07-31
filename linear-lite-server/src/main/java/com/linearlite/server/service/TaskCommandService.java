package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.dto.TaskMutationResponse;
import com.linearlite.server.dto.TaskStateChange;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class TaskCommandService {

    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "canceled", "duplicate");
    private static final Set<String> OPEN_STATUSES_FOR_PROGRESS_LINKAGE = Set.of(
            "backlog", "todo", "in_progress", "in_review");

    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final TaskFavoriteMapper taskFavoriteMapper;
    private final TaskActivityService taskActivityService;
    private final TaskPermissionGuard taskPermissionGuard;
    private final TaskSequenceService taskSequenceService;
    private final LabelService labelService;
    private final TaskQueryService taskQueryService;
    private final UserMapper userMapper;
    private final CodexDispatchService codexDispatchService;
    private final TaskStatusService taskStatusService;
    private final TaskHierarchyCompletionService taskHierarchyCompletionService;
    private ApplicationEventPublisher eventPublisher;

    public TaskCommandService(
            TaskMapper taskMapper,
            ProjectMapper projectMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            TaskActivityService taskActivityService,
            TaskPermissionGuard taskPermissionGuard,
            TaskSequenceService taskSequenceService,
            LabelService labelService,
            TaskQueryService taskQueryService,
            UserMapper userMapper,
            CodexDispatchService codexDispatchService,
            TaskStatusService taskStatusService,
            TaskHierarchyCompletionService taskHierarchyCompletionService) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.taskFavoriteMapper = taskFavoriteMapper;
        this.taskActivityService = taskActivityService;
        this.taskPermissionGuard = taskPermissionGuard;
        this.taskSequenceService = taskSequenceService;
        this.labelService = labelService;
        this.taskQueryService = taskQueryService;
        this.userMapper = userMapper;
        this.codexDispatchService = codexDispatchService;
        this.taskStatusService = taskStatusService;
        this.taskHierarchyCompletionService = taskHierarchyCompletionService;
    }

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskMutationResponse create(Long projectId, Long creatorId, Long parentId, String title, String description,
                       String status, String priority, Long assigneeId, LocalDateTime dueDate,
                       LocalDateTime plannedStartDate, Integer progressPercent, List<TaskLabelItemRequest> labels) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (creatorId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }

        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + projectId);
        }
        taskPermissionGuard.requireProjectMember(projectId, creatorId);

        if (parentId != null) {
            Task parent = taskMapper.selectById(parentId);
            if (parent == null) {
                throw new ResourceNotFoundException("父任务不存在: " + parentId);
            }
            if (!projectId.equals(parent.getProjectId())) {
                throw new IllegalArgumentException("父任务与当前项目不一致");
            }
        }

        long taskNumber = taskSequenceService.reserveTaskNumbers(projectId, project.getIdentifier(), 1);
        String taskKey = project.getIdentifier() + "-" + taskNumber;

        Task task = new Task();
        task.setTaskKey(taskKey);
        task.setTitle(title.trim());
        task.setDescription(description != null ? description.trim() : null);
        task.setStatus(status != null && !status.isBlank() ? status : "backlog");
        task.setPriority(priority != null && !priority.isBlank() ? priority : "medium");
        task.setProjectId(projectId);
        task.setParentId(parentId);
        task.setCreatorId(creatorId);
        task.setAssigneeId(assigneeId);
        task.setDueDate(dueDate);
        task.setPlannedStartDate(plannedStartDate);
        task.setProgressPercent(clampProgressPercent(progressPercent));
        normalizeProgressStatusForNewTask(task);
        LocalDateTime occurredAt = LocalDateTime.now();
        if (isTerminalStatus(task.getStatus())) {
            task.setCompletedAt(occurredAt);
        }

        taskMapper.insert(task);
        Task inserted = taskMapper.selectById(task.getId());
        taskActivityService.recordAction(inserted.getId(), creatorId, "created");
        if (labels != null) {
            labelService.replaceTaskLabels(inserted.getId(), projectId, labels);
            recordLabelsChange(inserted.getId(), creatorId, "", labelService.sortedNamesJoined(inserted.getId()));
        }
        if (isCodexAssignee(assigneeId)) {
            codexDispatchService.dispatchAssignedTask(inserted, creatorId);
        }
        taskQueryService.enrichForUser(Collections.singletonList(inserted), creatorId);
        publishSemanticIndexRequest(inserted.getId());
        List<TaskStateChange> ancestorChanges = taskHierarchyCompletionService.completeEligibleAncestors(
                inserted.getId(), creatorId, occurredAt);
        return new TaskMutationResponse(inserted, ancestorChanges);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskMutationResponse update(String taskKey, UpdateTaskRequest request, Long userId) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }

        Task existing = taskMapper.selectOne(
                new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey).last("FOR UPDATE"));
        if (existing == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskKey);
        }
        taskPermissionGuard.requireProjectMember(existing.getProjectId(), userId);

        UpdateWrapper<Task> wrapper = new UpdateWrapper<Task>()
                .eq("id", existing.getId());
        boolean hasUpdate = false;
        boolean semanticTextChanged = false;
        TaskService.ResolvedStatusProgress resolvedState = null;
        if (request.getTitle() != null) {
            String nextTitle = request.getTitle().trim();
            if (!nextTitle.equals(existing.getTitle())) {
                wrapper.set("title", nextTitle);
                hasUpdate = true;
                semanticTextChanged = true;
            }
        }
        if (request.getDescription() != null) {
            String desc = request.getDescription().trim();
            String nextDesc = desc.isEmpty() ? null : desc;
            if (!equalsNullable(nextDesc, existing.getDescription())) {
                wrapper.set("description", nextDesc);
                hasUpdate = true;
                semanticTextChanged = true;
            }
        }
        boolean touchStatusOrProgress =
                (request.getStatus() != null && !request.getStatus().isBlank())
                        || request.getProgressPercent() != null;
        if (touchStatusOrProgress) {
            String proposedStatus = existing.getStatus();
            if (request.getStatus() != null && !request.getStatus().isBlank()) {
                proposedStatus = request.getStatus().trim();
            }
            int proposedProgress = existing.getProgressPercent() != null ? existing.getProgressPercent() : 0;
            if (request.getProgressPercent() != null) {
                proposedProgress = clampProgressPercent(request.getProgressPercent());
            }
            boolean statusTouched = request.getStatus() != null && !request.getStatus().isBlank();
            boolean progressTouched = request.getProgressPercent() != null;
            resolvedState = TaskService.resolveStatusProgressLinkage(
                    existing, statusTouched, progressTouched, proposedStatus, proposedProgress);
        }
        if (request.getPriority() != null && !request.getPriority().isBlank()) {
            if (!request.getPriority().equals(existing.getPriority())) {
                wrapper.set("priority", request.getPriority());
                hasUpdate = true;
            }
        }
        if (Boolean.TRUE.equals(request.getClearAssignee())) {
            if (existing.getAssigneeId() != null) {
                wrapper.set("assignee_id", null);
                hasUpdate = true;
            }
            if (trimToNull(existing.getAssigneeDisplayName()) != null) {
                wrapper.set("assignee_display_name", null);
                hasUpdate = true;
            }
        } else if (request.getAssigneeId() != null) {
            if (!Objects.equals(request.getAssigneeId(), existing.getAssigneeId())) {
                wrapper.set("assignee_id", request.getAssigneeId());
                hasUpdate = true;
            }
            if (trimToNull(existing.getAssigneeDisplayName()) != null) {
                wrapper.set("assignee_display_name", null);
                hasUpdate = true;
            }
        }
        if (Boolean.TRUE.equals(request.getClearDueDate())) {
            if (existing.getDueDate() != null) {
                wrapper.set("due_date", null);
                hasUpdate = true;
            }
        } else if (request.getDueDate() != null) {
            if (!equalsNullable(request.getDueDate(), existing.getDueDate())) {
                wrapper.set("due_date", request.getDueDate());
                hasUpdate = true;
            }
        }
        if (Boolean.TRUE.equals(request.getClearPlannedStart())) {
            if (existing.getPlannedStartDate() != null) {
                wrapper.set("planned_start_date", null);
                hasUpdate = true;
            }
        } else if (request.getPlannedStartDate() != null) {
            if (!equalsNullable(request.getPlannedStartDate(), existing.getPlannedStartDate())) {
                wrapper.set("planned_start_date", request.getPlannedStartDate());
                hasUpdate = true;
            }
        }
        String oldLabelsJoined = null;
        if (request.getLabels() != null) {
            oldLabelsJoined = labelService.sortedNamesJoined(existing.getId());
        }

        if (Boolean.TRUE.equals(request.getClearParent())) {
            wrapper.set("parent_id", null);
            hasUpdate = true;
        } else if (request.getParentId() != null) {
            Long newParentId = request.getParentId();
            if (newParentId.equals(existing.getId())) {
                throw new IllegalArgumentException("父任务不能为自身");
            }
            Task parent = taskMapper.selectById(newParentId);
            if (parent == null) {
                throw new ResourceNotFoundException("父任务不存在: " + newParentId);
            }
            if (!parent.getProjectId().equals(existing.getProjectId())) {
                throw new IllegalArgumentException("父任务与当前任务项目不一致");
            }
            Set<Long> descendantIds = getDescendantIds(existing.getId());
            if (descendantIds.contains(newParentId)) {
                throw new IllegalArgumentException("不能将父任务设为自己的后代，会形成环");
            }
            wrapper.set("parent_id", newParentId);
            hasUpdate = true;
        }

        if (hasUpdate) {
            taskMapper.update(null, wrapper);
        }
        LocalDateTime occurredAt = LocalDateTime.now();
        if (resolvedState != null) {
            taskStatusService.updateState(
                    existing.getId(), resolvedState.status, resolvedState.progressPercent, userId, occurredAt);
        }
        if (request.getLabels() != null) {
            labelService.replaceTaskLabels(existing.getId(), existing.getProjectId(), request.getLabels());
        }
        Task updated = taskMapper.selectById(existing.getId());
        if (!Objects.equals(existing.getAssigneeId(), updated.getAssigneeId())
                && isCodexAssignee(updated.getAssigneeId())) {
            codexDispatchService.dispatchAssignedTask(updated, userId);
        }
        recordActivityForTaskChanges(existing, updated, userId);
        if (request.getLabels() != null) {
            recordLabelsChange(
                    updated.getId(),
                    userId,
                    oldLabelsJoined,
                    labelService.sortedNamesJoined(updated.getId()));
        }
        taskQueryService.enrichForUser(Collections.singletonList(updated), userId);
        if (semanticTextChanged) {
            publishSemanticIndexRequest(updated.getId());
        }
        List<TaskStateChange> ancestorChanges = new java.util.ArrayList<>();
        boolean parentChanged = !Objects.equals(existing.getParentId(), updated.getParentId());
        if (parentChanged && existing.getParentId() != null) {
            ancestorChanges.addAll(taskHierarchyCompletionService.completeEligibleParentChain(
                    existing.getParentId(), userId, occurredAt));
        }
        if (resolvedState != null || parentChanged) {
            ancestorChanges.addAll(taskHierarchyCompletionService.completeEligibleAncestors(
                    updated.getId(), userId, occurredAt));
        }
        return new TaskMutationResponse(updated, ancestorChanges);
    }

    private void publishSemanticIndexRequest(Long taskId) {
        if (eventPublisher != null) {
            // 同步监听器会在当前任务事务内持久化索引任务。
            eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                    ProjectContentType.TASK, taskId));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Task addFavorite(String taskKey, Long userId) {
        requireUserId(userId);
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        Long exists = taskFavoriteMapper.selectCount(
                new LambdaQueryWrapper<TaskFavorite>()
                        .eq(TaskFavorite::getUserId, userId)
                        .eq(TaskFavorite::getTaskId, task.getId()));
        if (exists == 0) {
            TaskFavorite favorite = new TaskFavorite();
            favorite.setUserId(userId);
            favorite.setTaskId(task.getId());
            taskFavoriteMapper.insert(favorite);
        }
        Task refreshed = taskMapper.selectById(task.getId());
        taskQueryService.enrichForUser(Collections.singletonList(refreshed), userId);
        return refreshed;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task removeFavorite(String taskKey, Long userId) {
        requireUserId(userId);
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        taskFavoriteMapper.delete(
                new LambdaQueryWrapper<TaskFavorite>()
                        .eq(TaskFavorite::getUserId, userId)
                        .eq(TaskFavorite::getTaskId, task.getId()));
        Task refreshed = taskMapper.selectById(task.getId());
        taskQueryService.enrichForUser(Collections.singletonList(refreshed), userId);
        return refreshed;
    }

    private Set<Long> getDescendantIds(Long taskId) {
        Set<Long> result = new HashSet<>();
        List<Long> current = Collections.singletonList(taskId);
        while (!current.isEmpty()) {
            List<Task> children = taskMapper.selectList(
                    new LambdaQueryWrapper<Task>().in(Task::getParentId, current));
            List<Long> next = new java.util.ArrayList<>();
            for (Task c : children) {
                if (result.add(c.getId())) {
                    next.add(c.getId());
                }
            }
            current = next;
        }
        return result;
    }

    private static boolean isTerminalStatus(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.toLowerCase());
    }

    private boolean isCodexAssignee(Long assigneeId) {
        if (assigneeId == null) {
            return false;
        }
        User assignee = userMapper.selectById(assigneeId);
        if (assignee == null) {
            throw new ResourceNotFoundException("负责人不存在: " + assigneeId);
        }
        return User.TYPE_CODEX.equals(assignee.getUserType());
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

    private void recordActivityForTaskChanges(Task existing, Task updated, Long userId) {
        if (userId == null || existing == null || updated == null) {
            return;
        }
        recordFieldChange(existing.getId(), userId, "title", existing.getTitle(), updated.getTitle());
        recordFieldChange(existing.getId(), userId, "priority", existing.getPriority(), updated.getPriority());
        if (!equalsNullable(existing.getAssigneeId(), updated.getAssigneeId())) {
            taskActivityService.recordAssigneeChange(existing.getId(), userId, existing.getAssigneeId(), updated.getAssigneeId());
        }
        recordFieldChange(existing.getId(), userId, "dueDate", stringify(existing.getDueDate()), stringify(updated.getDueDate()));
        recordFieldChange(
                existing.getId(),
                userId,
                "plannedStartDate",
                stringify(existing.getPlannedStartDate()),
                stringify(updated.getPlannedStartDate()));
    }

    private void recordFieldChange(Long taskId, Long userId, String fieldName, String oldValue, String newValue) {
        if (equalsNullable(oldValue, newValue)) {
            return;
        }
        taskActivityService.recordFieldChange(taskId, userId, fieldName, oldValue, newValue);
    }

    private void recordLabelsChange(Long taskId, Long userId, String oldJoined, String newJoined) {
        if (userId == null) {
            return;
        }
        String o = oldJoined == null || oldJoined.isEmpty() ? null : oldJoined;
        String n = newJoined == null || newJoined.isEmpty() ? null : newJoined;
        recordFieldChange(taskId, userId, "labels", o, n);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int clampProgressPercent(Integer value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, value));
    }

    private static String stringifyProgress(Integer value) {
        return String.valueOf(value != null ? value : 0);
    }
}
