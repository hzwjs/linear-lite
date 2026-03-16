package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskActivityResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskActivity;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TaskActivityService {

    private final TaskActivityMapper taskActivityMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;

    public TaskActivityService(TaskActivityMapper taskActivityMapper, TaskMapper taskMapper, UserMapper userMapper) {
        this.taskActivityMapper = taskActivityMapper;
        this.taskMapper = taskMapper;
        this.userMapper = userMapper;
    }

    public void recordAction(Long taskId, Long userId, String actionType) {
        TaskActivity activity = new TaskActivity();
        activity.setTaskId(taskId);
        activity.setUserId(userId);
        activity.setActionType(actionType);
        taskActivityMapper.insert(activity);
    }

    public void recordFieldChange(Long taskId, Long userId, String fieldName, String oldValue, String newValue) {
        TaskActivity activity = new TaskActivity();
        activity.setTaskId(taskId);
        activity.setUserId(userId);
        activity.setActionType("changed");
        activity.setFieldName(fieldName);
        activity.setOldValue(oldValue);
        activity.setNewValue(newValue);
        taskActivityMapper.insert(activity);
    }

    public void recordAssigneeChange(Long taskId, Long userId, Long oldAssigneeId, Long newAssigneeId) {
        List<Long> ids = Stream.of(oldAssigneeId, newAssigneeId).filter(id -> id != null).distinct().toList();
        Map<Long, String> namesById = resolveUserNames(ids);
        recordFieldChange(
                taskId,
                userId,
                "assigneeId",
                oldAssigneeId == null ? null : namesById.getOrDefault(oldAssigneeId, "Unknown"),
                newAssigneeId == null ? null : namesById.getOrDefault(newAssigneeId, "Unknown"));
    }

    public List<TaskActivityResponse> listByTaskKey(String taskKey) {
        Task task = taskMapper.selectOne(
                new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (task == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskKey);
        }
        List<TaskActivity> activities = taskActivityMapper.selectList(
                new LambdaQueryWrapper<TaskActivity>()
                        .eq(TaskActivity::getTaskId, task.getId())
                        .orderByDesc(TaskActivity::getCreatedAt, TaskActivity::getId));
        if (activities.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> userIds = activities.stream()
                .map(TaskActivity::getUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, User> usersById = userIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return activities.stream().map(activity -> {
            TaskActivityResponse response = new TaskActivityResponse();
            response.setId(activity.getId());
            response.setActionType(activity.getActionType());
            response.setFieldName(activity.getFieldName());
            response.setOldValue(activity.getOldValue());
            response.setNewValue(activity.getNewValue());
            response.setCreatedAt(activity.getCreatedAt());
            User actor = usersById.get(activity.getUserId());
            response.setActorName(actor != null ? actor.getUsername() : "Someone");
            return response;
        }).toList();
    }

    private Map<Long, String> resolveUserNames(List<Long> userIds) {
        List<Long> filtered = userIds.stream().filter(id -> id != null).distinct().toList();
        if (filtered.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(filtered).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
    }
}
