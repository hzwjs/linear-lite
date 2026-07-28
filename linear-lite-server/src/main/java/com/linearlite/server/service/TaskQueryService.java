package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.dto.TaskSubIssueCount;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Service
public class TaskQueryService {

    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "canceled", "duplicate");
    private static final int MAX_SEARCH_QUERY_LENGTH = 200;

    private final TaskMapper taskMapper;
    private final TaskFavoriteMapper taskFavoriteMapper;
    private final LabelService labelService;
    private final TaskPermissionGuard taskPermissionGuard;
    private final TaskSemanticSearchService semanticSearchService;
    private final ProjectMemberMapper projectMemberMapper;

    public TaskQueryService(
            TaskMapper taskMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            LabelService labelService,
            TaskPermissionGuard taskPermissionGuard) {
        this(taskMapper, taskFavoriteMapper, labelService, taskPermissionGuard, null, null);
    }

    public TaskQueryService(
            TaskMapper taskMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            LabelService labelService,
            TaskPermissionGuard taskPermissionGuard,
            TaskSemanticSearchService semanticSearchService) {
        this(taskMapper, taskFavoriteMapper, labelService, taskPermissionGuard, semanticSearchService, null);
    }

    @Autowired
    public TaskQueryService(
            TaskMapper taskMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            LabelService labelService,
            TaskPermissionGuard taskPermissionGuard,
            TaskSemanticSearchService semanticSearchService,
            ProjectMemberMapper projectMemberMapper) {
        this.taskMapper = taskMapper;
        this.taskFavoriteMapper = taskFavoriteMapper;
        this.labelService = labelService;
        this.taskPermissionGuard = taskPermissionGuard;
        this.semanticSearchService = semanticSearchService;
        this.projectMemberMapper = projectMemberMapper;
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
        List<TaskListItemResponse> response = taskMapper.selectListItemResponses(projectId, topLevelOnly, parentId, userId).stream()
                .filter(item -> item != null && item.getId() != null)
                .toList();
        if (response.isEmpty()) {
            taskPermissionGuard.requireProjectMember(projectId, userId);
            return response;
        }
        enrichListItems(response, projectId, userId, topLevelOnly, parentId);
        return response;
    }

    /**
     * 语义搜索标题与描述，只返回 task key，避免把描述富文本带入轻量任务列表。
     */
    public List<Task> searchTasks(String query, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        if (normalized.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw new IllegalArgumentException("搜索内容不能超过 " + MAX_SEARCH_QUERY_LENGTH + " 个字符");
        }
        if (semanticSearchService == null) {
            throw new IllegalStateException("语义搜索服务未配置");
        }
        if (projectMemberMapper == null) {
            throw new IllegalStateException("项目成员服务未配置");
        }
        List<Long> projectIds = projectMemberMapper.selectList(
                        new LambdaQueryWrapper<com.linearlite.server.entity.ProjectMember>()
                                .eq(com.linearlite.server.entity.ProjectMember::getUserId, userId))
                .stream().map(com.linearlite.server.entity.ProjectMember::getProjectId).distinct().toList();
        if (projectIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Task> accessibleTasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .in(Task::getProjectId, projectIds)
                        .orderByAsc(Task::getId));
        List<String> taskKeys = semanticSearchService.search(projectIds, normalized);
        String literalQuery = normalized.toLowerCase(Locale.ROOT);
        Map<String, Task> ordered = new LinkedHashMap<>();
        // 字面标题命中优先，向量结果只补充未命中的任务。
        accessibleTasks.stream()
                .filter(task -> containsIgnoreCase(task.getTitle(), literalQuery))
                .forEach(task -> ordered.put(task.getTaskKey(), task));
        // 描述只使用 BlockNote/富文本中用户可见的文本，避免命中 JSON 结构与样式元数据。
        accessibleTasks.stream()
                .filter(task -> containsIgnoreCase(
                        TaskDescriptionTextExtractor.extract(task.getDescription()), literalQuery))
                .forEach(task -> ordered.putIfAbsent(task.getTaskKey(), task));
        Map<String, Task> byKey = accessibleTasks.stream()
                .filter(task -> task.getTaskKey() != null)
                .collect(Collectors.toMap(Task::getTaskKey, task -> task));
        taskKeys.stream().map(byKey::get).filter(Objects::nonNull)
                .forEach(task -> ordered.putIfAbsent(task.getTaskKey(), task));
        return List.copyOf(ordered.values());
    }

    /** 兼容旧调用方：仍使用语义检索，但只返回指定项目的 key。 */
    public List<String> searchTaskKeys(Long projectId, String query, Long userId) {
        if (projectId == null) throw new IllegalArgumentException("projectId 不能为空");
        taskPermissionGuard.requireProjectMember(projectId, userId);
        return searchTasks(query, userId).stream()
                .filter(task -> projectId.equals(task.getProjectId()))
                .map(Task::getTaskKey)
                .toList();
    }

    private static boolean containsIgnoreCase(String text, String lowercaseQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowercaseQuery);
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
