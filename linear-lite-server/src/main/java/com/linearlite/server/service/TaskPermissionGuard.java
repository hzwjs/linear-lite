package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

@Service
public class TaskPermissionGuard {

    private final TaskMapper taskMapper;
    private final ProjectAccessGuard projectAccessGuard;

    public TaskPermissionGuard(TaskMapper taskMapper, ProjectAccessGuard projectAccessGuard) {
        this.taskMapper = taskMapper;
        this.projectAccessGuard = projectAccessGuard;
    }

    public void requireProjectMember(Long projectId, Long userId) {
        projectAccessGuard.requireMember(projectId, userId);
    }

    public Task requireTaskAccessByKey(String taskKey, Long userId) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (task == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskKey);
        }
        requireProjectMember(task.getProjectId(), userId);
        return task;
    }
}
