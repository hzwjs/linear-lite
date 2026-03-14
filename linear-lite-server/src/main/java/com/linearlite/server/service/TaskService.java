package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务业务：按项目列表、创建（生成 task_key）、更新；支持父子任务与子任务计数。
 */
@Service
public class TaskService {

    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "canceled");

    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;
    private final TaskFavoriteMapper taskFavoriteMapper;
    private final TaskActivityService taskActivityService;

    public TaskService(
            TaskMapper taskMapper,
            ProjectMapper projectMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            TaskActivityService taskActivityService) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
        this.taskFavoriteMapper = taskFavoriteMapper;
        this.taskActivityService = taskActivityService;
    }

    /**
     * 按项目 ID 返回任务列表，支持仅顶层或按父任务过滤；按 id 升序。返回前填充 subIssueCount、completedSubIssueCount。
     *
     * @param projectId    必填
     * @param topLevelOnly  true 时仅返回 parent_id IS NULL
     * @param parentId      非空时仅返回该父任务下的直接子任务（与 topLevelOnly 互斥，优先 parentId）
     */
    public List<Task> listByProjectId(Long projectId, Boolean topLevelOnly, Long parentId, Long userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, projectId)
                .orderByAsc(Task::getId);
        if (parentId != null) {
            wrapper.eq(Task::getParentId, parentId);
        } else if (Boolean.TRUE.equals(topLevelOnly)) {
            wrapper.isNull(Task::getParentId);
        }
        List<Task> list = taskMapper.selectList(wrapper);
        fillSubIssueCounts(list);
        fillFavoriteState(list, userId);
        return list;
    }

    /**
     * 创建任务：绑定 creator_id，生成带项目前缀的 task_key。若 parentId 非空则校验父任务存在且属于同一项目。
     */
    @Transactional(rollbackFor = Exception.class)
    public Task create(Long projectId, Long creatorId, Long parentId, String title, String description,
                       String status, String priority, Long assigneeId, LocalDateTime dueDate) {
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

        if (parentId != null) {
            Task parent = taskMapper.selectById(parentId);
            if (parent == null) {
                throw new ResourceNotFoundException("父任务不存在: " + parentId);
            }
            if (!projectId.equals(parent.getProjectId())) {
                throw new IllegalArgumentException("父任务与当前项目不一致");
            }
        }

        long count = taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().eq(Task::getProjectId, projectId));
        String taskKey = project.getIdentifier() + "-" + (count + 1);

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

        taskMapper.insert(task);
        Task inserted = taskMapper.selectById(task.getId());
        taskActivityService.recordAction(inserted.getId(), creatorId, "created");
        fillSubIssueCounts(Collections.singletonList(inserted));
        inserted.setFavorited(false);
        return inserted;
    }

    /**
     * 批量填充直接子任务数量与已完成数量（仅直接子任务）。
     */
    private void fillSubIssueCounts(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<Long> parentIds = tasks.stream().map(Task::getId).distinct().collect(Collectors.toList());
        if (parentIds.isEmpty()) {
            return;
        }
        List<Task> children = taskMapper.selectList(
                new LambdaQueryWrapper<Task>().in(Task::getParentId, parentIds));
        Map<Long, Integer> totalByParent = new HashMap<>();
        Map<Long, Integer> completedByParent = new HashMap<>();
        for (Task c : children) {
            totalByParent.merge(c.getParentId(), 1, Integer::sum);
            if (c.getStatus() != null && TERMINAL_STATUSES.contains(c.getStatus().toLowerCase())) {
                completedByParent.merge(c.getParentId(), 1, Integer::sum);
            }
        }
        for (Task t : tasks) {
            t.setSubIssueCount(totalByParent.getOrDefault(t.getId(), 0));
            t.setCompletedSubIssueCount(completedByParent.getOrDefault(t.getId(), 0));
        }
    }

    /**
     * 按 task_key（如 ENG-1）更新任务。仅更新请求体中非 null 的字段。
     */
    public Task update(String taskKey, UpdateTaskRequest request) {
        return update(taskKey, request, null);
    }

    public Task update(String taskKey, UpdateTaskRequest request, Long userId) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }

        Task existing = taskMapper.selectOne(
                new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (existing == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskKey);
        }

        UpdateWrapper<Task> wrapper = new UpdateWrapper<Task>()
                .eq("id", existing.getId());
        boolean hasUpdate = false;
        if (request.getTitle() != null) {
            String nextTitle = request.getTitle().trim();
            if (!nextTitle.equals(existing.getTitle())) {
                wrapper.set("title", nextTitle);
                hasUpdate = true;
            }
        }
        if (request.getDescription() != null) {
            String desc = request.getDescription().trim();
            String nextDesc = desc.isEmpty() ? null : desc;
            if (!equalsNullable(nextDesc, existing.getDescription())) {
                wrapper.set("description", nextDesc);
                hasUpdate = true;
            }
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            String newStatus = request.getStatus();
            if (!newStatus.equals(existing.getStatus())) {
                wrapper.set("status", newStatus);
                hasUpdate = true;
                boolean wasTerminal = isTerminalStatus(existing.getStatus());
                boolean nowTerminal = isTerminalStatus(newStatus);
                if (nowTerminal) {
                    wrapper.set("completed_at", LocalDateTime.now());
                } else if (wasTerminal) {
                    wrapper.set("completed_at", null);
                }
            }
        }
        if (request.getPriority() != null && !request.getPriority().isBlank()) {
            if (!request.getPriority().equals(existing.getPriority())) {
                wrapper.set("priority", request.getPriority());
                hasUpdate = true;
            }
        }
        if (request.getAssigneeId() != null) {
            if (!equalsNullable(request.getAssigneeId(), existing.getAssigneeId())) {
                wrapper.set("assignee_id", request.getAssigneeId());
                hasUpdate = true;
            }
        }
        if (request.getDueDate() != null) {
            if (!equalsNullable(request.getDueDate(), existing.getDueDate())) {
                wrapper.set("due_date", request.getDueDate());
                hasUpdate = true;
            }
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
        Task updated = taskMapper.selectById(existing.getId());
        recordActivityForTaskChanges(existing, updated, userId);
        fillSubIssueCounts(Collections.singletonList(updated));
        fillFavoriteState(Collections.singletonList(updated), userId);
        return updated;
    }

    public List<Task> listFavorites(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        List<TaskFavorite> favorites = taskFavoriteMapper.selectList(
                new LambdaQueryWrapper<TaskFavorite>()
                        .eq(TaskFavorite::getUserId, userId)
                        .orderByDesc(TaskFavorite::getCreatedAt));
        if (favorites.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> taskIds = favorites.stream().map(TaskFavorite::getTaskId).distinct().toList();
        List<Task> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>().in(Task::getId, taskIds));
        Map<Long, Task> taskById = tasks.stream().collect(Collectors.toMap(Task::getId, task -> task));
        List<Task> ordered = favorites.stream()
                .map(favorite -> taskById.get(favorite.getTaskId()))
                .filter(task -> task != null)
                .collect(Collectors.toList());
        fillSubIssueCounts(ordered);
        fillFavoriteState(ordered, userId);
        return ordered;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task addFavorite(String taskKey, Long userId) {
        Task task = requireTaskByKey(taskKey);
        requireUserId(userId);
        Long exists = taskFavoriteMapper.selectCount(
                new LambdaQueryWrapper<TaskFavorite>()
                        .eq(TaskFavorite::getUserId, userId)
                        .eq(TaskFavorite::getTaskId, task.getId()));
        if (exists == 0) {
            TaskFavorite favorite = new TaskFavorite();
            favorite.setUserId(userId);
            favorite.setTaskId(task.getId());
            taskFavoriteMapper.insert(favorite);
            taskActivityService.recordAction(task.getId(), userId, "favorited");
        }
        Task refreshed = taskMapper.selectById(task.getId());
        fillSubIssueCounts(Collections.singletonList(refreshed));
        fillFavoriteState(Collections.singletonList(refreshed), userId);
        return refreshed;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task removeFavorite(String taskKey, Long userId) {
        Task task = requireTaskByKey(taskKey);
        requireUserId(userId);
        taskFavoriteMapper.delete(
                new LambdaQueryWrapper<TaskFavorite>()
                        .eq(TaskFavorite::getUserId, userId)
                        .eq(TaskFavorite::getTaskId, task.getId()));
        taskActivityService.recordAction(task.getId(), userId, "unfavorited");
        Task refreshed = taskMapper.selectById(task.getId());
        fillSubIssueCounts(Collections.singletonList(refreshed));
        fillFavoriteState(Collections.singletonList(refreshed), userId);
        return refreshed;
    }

    /** 返回以 taskId 为根的所有后代任务 id（不含自身）。 */
    private Set<Long> getDescendantIds(Long taskId) {
        Set<Long> result = new HashSet<>();
        List<Long> current = Collections.singletonList(taskId);
        while (!current.isEmpty()) {
            List<Task> children = taskMapper.selectList(
                    new LambdaQueryWrapper<Task>().in(Task::getParentId, current));
            List<Long> next = new ArrayList<>();
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

    private void fillFavoriteState(List<Task> tasks, Long userId) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        if (userId == null) {
            for (Task task : tasks) {
                task.setFavorited(false);
            }
            return;
        }
        List<Long> taskIds = tasks.stream().map(Task::getId).filter(id -> id != null).distinct().toList();
        if (taskIds.isEmpty()) {
            return;
        }
        List<TaskFavorite> favorites = taskFavoriteMapper.selectList(
                new LambdaQueryWrapper<TaskFavorite>()
                        .eq(TaskFavorite::getUserId, userId)
                        .in(TaskFavorite::getTaskId, taskIds));
        Set<Long> favoriteTaskIds = favorites.stream().map(TaskFavorite::getTaskId).collect(Collectors.toSet());
        for (Task task : tasks) {
            task.setFavorited(favoriteTaskIds.contains(task.getId()));
        }
    }

    private Task requireTaskByKey(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (task == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskKey);
        }
        return task;
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
        recordFieldChange(existing.getId(), userId, "description", existing.getDescription(), updated.getDescription());
        recordFieldChange(existing.getId(), userId, "status", existing.getStatus(), updated.getStatus());
        recordFieldChange(existing.getId(), userId, "priority", existing.getPriority(), updated.getPriority());
        if (!equalsNullable(existing.getAssigneeId(), updated.getAssigneeId())) {
            taskActivityService.recordAssigneeChange(existing.getId(), userId, existing.getAssigneeId(), updated.getAssigneeId());
        }
        recordFieldChange(existing.getId(), userId, "dueDate", stringify(existing.getDueDate()), stringify(updated.getDueDate()));
    }

    private void recordFieldChange(Long taskId, Long userId, String fieldName, String oldValue, String newValue) {
        if (equalsNullable(oldValue, newValue)) {
            return;
        }
        taskActivityService.recordFieldChange(taskId, userId, fieldName, oldValue, newValue);
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
