package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

@Service
public class TaskPermissionGuard {

    private final TaskMapper taskMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public TaskPermissionGuard(TaskMapper taskMapper, ProjectMemberMapper projectMemberMapper) {
        this.taskMapper = taskMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public void requireProjectMember(Long projectId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        Long count = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)
        );
        if (count == null || count == 0) {
            throw new ForbiddenOperationException("你不是该项目成员");
        }
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
