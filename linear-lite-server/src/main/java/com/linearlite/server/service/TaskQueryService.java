package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        enrichForUser(list, userId);
        return list;
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
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        fillSubIssueCounts(tasks);
        fillFavoriteState(tasks, userId);
        labelService.fillLabelsForTasks(tasks);
    }

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
}
