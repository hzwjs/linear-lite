package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.dto.TaskSubIssueCount;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Service
public class TaskQueryService {

    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "canceled");

    private final TaskMapper taskMapper;
    private final TaskFavoriteMapper taskFavoriteMapper;
    private final LabelService labelService;
    private final TaskPermissionGuard taskPermissionGuard;

    public TaskQueryService(
            TaskMapper taskMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            LabelService labelService,
            TaskPermissionGuard taskPermissionGuard) {
        this.taskMapper = taskMapper;
        this.taskFavoriteMapper = taskFavoriteMapper;
        this.labelService = labelService;
        this.taskPermissionGuard = taskPermissionGuard;
    }

    public List<Task> listByProjectId(Long projectId, Boolean topLevelOnly, Long parentId, Long userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        taskPermissionGuard.requireProjectMember(projectId, userId);
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, projectId)
                .orderByAsc(Task::getId);
        if (parentId != null) {
            wrapper.eq(Task::getParentId, parentId);
        } else if (Boolean.TRUE.equals(topLevelOnly)) {
            wrapper.isNull(Task::getParentId);
        }
        List<Task> list = taskMapper.selectList(wrapper);
        enrichForUser(list, userId, canComputeSubIssueCountsFromLoadedTasks(topLevelOnly, parentId));
        return list;
    }

    public List<TaskListItemResponse> listItemsByProjectId(Long projectId, Boolean topLevelOnly, Long parentId, Long userId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        List<TaskListItemResponse> response = taskMapper.selectListItemResponses(projectId, topLevelOnly, parentId, userId);
        if (response.isEmpty()) {
            throw new ForbiddenOperationException("你不是该项目成员");
        }
        response = response.stream().filter(item -> item.getId() != null).toList();
        enrichListItems(response, projectId, userId, topLevelOnly, parentId);
        return response;
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
        enrichForUser(ordered, userId);
        return ordered;
    }

    public Task getByKeyOrThrow(String taskKey, Long userId) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        enrichForUser(Collections.singletonList(task), userId);
        return task;
    }

    public void enrichForUser(List<Task> tasks, Long userId) {
        enrichForUser(tasks, userId, false);
    }

    private void enrichForUser(List<Task> tasks, Long userId, boolean computeSubIssueCountsFromLoadedTasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        fillSubIssueCounts(tasks, computeSubIssueCountsFromLoadedTasks);
        fillFavoriteState(tasks, userId);
        labelService.fillLabelsForTasks(tasks);
    }

    private void enrichListItems(List<TaskListItemResponse> tasks, Long projectId, Long userId, Boolean topLevelOnly, Long parentId) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        fillListItemSubIssueCounts(tasks, canComputeSubIssueCountsFromLoadedTasks(topLevelOnly, parentId));
        CompletableFuture<Void> favoriteFuture = CompletableFuture.runAsync(() -> {
            fillListItemFavoriteState(tasks, userId);
        });
        CompletableFuture<Void> labelFuture = CompletableFuture.runAsync(() -> {
            labelService.fillLabelsForTaskListItems(
                    tasks,
                    canComputeSubIssueCountsFromLoadedTasks(topLevelOnly, parentId) ? projectId : null);
        });
        joinEnrichment(favoriteFuture);
        joinEnrichment(labelFuture);
    }

    private void fillListItemSubIssueCounts(List<TaskListItemResponse> tasks, boolean computeFromLoadedTasks) {
        List<Long> parentIds = tasks.stream().map(TaskListItemResponse::getId).distinct().collect(Collectors.toList());
        if (parentIds.isEmpty()) {
            return;
        }
        if (!computeFromLoadedTasks) {
            List<TaskSubIssueCount> rows = taskMapper.selectSubIssueCounts(parentIds);
            Map<Long, TaskSubIssueCount> byParent = rows.stream()
                    .collect(Collectors.toMap(TaskSubIssueCount::getParentId, row -> row));
            for (TaskListItemResponse t : tasks) {
                TaskSubIssueCount row = byParent.get(t.getId());
                t.setSubIssueCount(row == null ? 0 : toInt(row.getTotalCount()));
                t.setCompletedSubIssueCount(row == null ? 0 : toInt(row.getCompletedCount()));
            }
            return;
        }
        Map<Long, Integer> totalByParent = new HashMap<>();
        Map<Long, Integer> completedByParent = new HashMap<>();
        Set<Long> parentIdSet = Set.copyOf(parentIds);
        for (TaskListItemResponse c : tasks) {
            if (c.getParentId() == null || !parentIdSet.contains(c.getParentId())) {
                continue;
            }
            totalByParent.merge(c.getParentId(), 1, Integer::sum);
            if (c.getStatus() != null && TERMINAL_STATUSES.contains(c.getStatus().toLowerCase())) {
                completedByParent.merge(c.getParentId(), 1, Integer::sum);
            }
        }
        for (TaskListItemResponse t : tasks) {
            t.setSubIssueCount(totalByParent.getOrDefault(t.getId(), 0));
            t.setCompletedSubIssueCount(completedByParent.getOrDefault(t.getId(), 0));
        }
    }

    private void fillListItemFavoriteState(List<TaskListItemResponse> tasks, Long userId) {
        if (userId == null) {
            for (TaskListItemResponse task : tasks) {
                task.setFavorited(false);
            }
            return;
        }
        List<Long> taskIds = tasks.stream().map(TaskListItemResponse::getId).filter(id -> id != null).distinct().toList();
        if (taskIds.isEmpty()) {
            return;
        }
        Set<Long> visibleTaskIds = Set.copyOf(taskIds);
        Set<Long> favoriteTaskIds = taskFavoriteMapper.selectFavoriteTaskIdsByUser(userId)
                .stream()
                .filter(visibleTaskIds::contains)
                .collect(Collectors.toSet());
        for (TaskListItemResponse task : tasks) {
            task.setFavorited(favoriteTaskIds.contains(task.getId()));
        }
    }

    private void fillSubIssueCounts(List<Task> tasks, boolean computeFromLoadedTasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<Long> parentIds = tasks.stream().map(Task::getId).distinct().collect(Collectors.toList());
        if (parentIds.isEmpty()) {
            return;
        }
        if (!computeFromLoadedTasks) {
            fillSubIssueCountsFromAggregate(tasks, parentIds);
            return;
        }
        Map<Long, Integer> totalByParent = new HashMap<>();
        Map<Long, Integer> completedByParent = new HashMap<>();
        Set<Long> parentIdSet = Set.copyOf(parentIds);
        for (Task c : tasks) {
            if (c.getParentId() == null || !parentIdSet.contains(c.getParentId())) {
                continue;
            }
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

    private void fillSubIssueCountsFromAggregate(List<Task> tasks, List<Long> parentIds) {
        List<TaskSubIssueCount> rows = taskMapper.selectSubIssueCounts(parentIds);
        Map<Long, TaskSubIssueCount> byParent = rows.stream()
                .collect(Collectors.toMap(TaskSubIssueCount::getParentId, row -> row));
        for (Task t : tasks) {
            TaskSubIssueCount row = byParent.get(t.getId());
            t.setSubIssueCount(row == null ? 0 : toInt(row.getTotalCount()));
            t.setCompletedSubIssueCount(row == null ? 0 : toInt(row.getCompletedCount()));
        }
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
        Set<Long> visibleTaskIds = Set.copyOf(taskIds);
        Set<Long> favoriteTaskIds = taskFavoriteMapper.selectFavoriteTaskIdsByUser(userId)
                .stream()
                .filter(visibleTaskIds::contains)
                .collect(Collectors.toSet());
        for (Task task : tasks) {
            task.setFavorited(favoriteTaskIds.contains(task.getId()));
        }
    }

    private static boolean canComputeSubIssueCountsFromLoadedTasks(Boolean topLevelOnly, Long parentId) {
        return parentId == null && !Boolean.TRUE.equals(topLevelOnly);
    }

    private static int toInt(Long value) {
        if (value == null) {
            return 0;
        }
        return Math.toIntExact(value);
    }

    private static void joinEnrichment(CompletableFuture<Void> future) {
        try {
            future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
