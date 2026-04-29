package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linearlite.server.dto.TaskActivityResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskActivity;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TaskActivityService {

    private final TaskActivityMapper taskActivityMapper;
    private final UserMapper userMapper;
    private final TaskPermissionGuard taskPermissionGuard;

    public TaskActivityService(
            TaskActivityMapper taskActivityMapper,
            UserMapper userMapper,
            TaskPermissionGuard taskPermissionGuard) {
        this.taskActivityMapper = taskActivityMapper;
        this.userMapper = userMapper;
        this.taskPermissionGuard = taskPermissionGuard;
    }

    public void recordAction(Long taskId, Long userId, String actionType) {
        TaskActivity activity = new TaskActivity();
        activity.setTaskId(taskId);
        activity.setUserId(userId);
        activity.setActionType(actionType);
        taskActivityMapper.insert(activity);
    }

    private static final int CHANGED_FIELD_COALESCE_MINUTES = 2;
    private static final int DESCRIPTION_PREVIEW_CHARS = 160;
    private static final int GENERAL_VALUE_MAX_CHARS = 256;
    private static final Set<String> COALESCE_FIELDS = Set.of(
            "description", "title", "progressPercent", "dueDate", "plannedStartDate", "labels");

    /**
     * 记录描述变更。若该任务在最近几分钟内已有同一用户的「changed description」记录，则合并为一条（只更新 newValue 与时间），避免一次编辑产生多条活动。
     */
    public void recordDescriptionChange(Long taskId, Long userId, String oldValue, String newValue) {
        recordFieldChange(taskId, userId, "description", oldValue, newValue);
    }

    public void recordFieldChange(Long taskId, Long userId, String fieldName, String oldValue, String newValue) {
        String compactOld = compactValue(fieldName, oldValue);
        String compactNew = compactValue(fieldName, newValue);
        if (Objects.equals(compactOld, compactNew)) {
            return;
        }
        if (shouldCoalesce(fieldName)) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusMinutes(CHANGED_FIELD_COALESCE_MINUTES);
            Page<TaskActivity> page = new Page<>(1, 1);
            List<TaskActivity> last = taskActivityMapper.selectPage(page,
                    new LambdaQueryWrapper<TaskActivity>()
                            .eq(TaskActivity::getTaskId, taskId)
                            .eq(TaskActivity::getUserId, userId)
                            .eq(TaskActivity::getActionType, "changed")
                            .eq(TaskActivity::getFieldName, fieldName)
                            .orderByDesc(TaskActivity::getCreatedAt))
                    .getRecords();
            if (!last.isEmpty()) {
                TaskActivity act = last.get(0);
                if (!act.getCreatedAt().isBefore(cutoff)) {
                    act.setNewValue(compactNew);
                    act.setCreatedAt(now);
                    taskActivityMapper.updateById(act);
                    return;
                }
            }
        }
        TaskActivity activity = new TaskActivity();
        activity.setTaskId(taskId);
        activity.setUserId(userId);
        activity.setActionType("changed");
        activity.setFieldName(fieldName);
        activity.setOldValue(compactOld);
        activity.setNewValue(compactNew);
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

    public List<TaskActivityResponse> listByTaskKey(String taskKey, Long userId, int limit) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        Page<TaskActivity> page = new Page<>(1, limit);
        List<TaskActivity> activities = taskActivityMapper.selectPage(page,
                new LambdaQueryWrapper<TaskActivity>()
                        .eq(TaskActivity::getTaskId, task.getId())
                        .orderByDesc(TaskActivity::getCreatedAt, TaskActivity::getId))
                .getRecords();
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

    private static boolean shouldCoalesce(String fieldName) {
        return fieldName != null && COALESCE_FIELDS.contains(fieldName);
    }

    private static String compactValue(String fieldName, String value) {
        if (value == null) {
            return null;
        }
        if ("description".equals(fieldName)) {
            return compactDescription(value);
        }
        if (value.length() <= GENERAL_VALUE_MAX_CHARS) {
            return value;
        }
        return value.substring(0, GENERAL_VALUE_MAX_CHARS) + "… [len=" + value.length() + "]";
    }

    private static String compactDescription(String value) {
        if (value.length() <= DESCRIPTION_PREVIEW_CHARS) {
            return value;
        }
        return value.substring(0, DESCRIPTION_PREVIEW_CHARS) + "… [len=" + value.length() + "]";
    }

}
