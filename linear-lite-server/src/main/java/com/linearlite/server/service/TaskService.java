package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务业务：按项目列表、创建（生成 task_key）、更新。
 */
@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final ProjectMapper projectMapper;

    public TaskService(TaskMapper taskMapper, ProjectMapper projectMapper) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 按项目 ID 返回任务列表，按 id 升序。
     */
    public List<Task> listByProjectId(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        return taskMapper.selectList(
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, projectId)
                        .orderByAsc(Task::getId));
    }

    /**
     * 创建任务：绑定 creator_id，生成带项目前缀的 task_key（如 ENG-1、PROD-2）。
     * 规则：同一项目下已有任务数量为 n 时，新 task_key = 项目 identifier + "-" + (n+1)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Task create(Long projectId, Long creatorId, String title, String description,
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
        task.setCreatorId(creatorId);
        task.setAssigneeId(assigneeId);
        task.setDueDate(dueDate);

        taskMapper.insert(task);
        return taskMapper.selectById(task.getId());
    }

    /**
     * 按 task_key（如 ENG-1）更新任务。仅更新请求体中非 null 的字段。
     */
    public Task update(String taskKey, UpdateTaskRequest request) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }

        Task existing = taskMapper.selectOne(
                new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (existing == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskKey);
        }

        LambdaUpdateWrapper<Task> wrapper = new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, existing.getId());
        boolean hasUpdate = false;
        if (request.getTitle() != null) {
            wrapper.set(Task::getTitle, request.getTitle().trim());
            hasUpdate = true;
        }
        if (request.getDescription() != null) {
            wrapper.set(Task::getDescription, request.getDescription().trim());
            hasUpdate = true;
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            String newStatus = request.getStatus();
            wrapper.set(Task::getStatus, newStatus);
            hasUpdate = true;
            boolean wasTerminal = isTerminalStatus(existing.getStatus());
            boolean nowTerminal = isTerminalStatus(newStatus);
            if (nowTerminal) {
                wrapper.set(Task::getCompletedAt, LocalDateTime.now());
            } else if (wasTerminal) {
                wrapper.set(Task::getCompletedAt, null);
            }
        }
        if (request.getPriority() != null && !request.getPriority().isBlank()) {
            wrapper.set(Task::getPriority, request.getPriority());
            hasUpdate = true;
        }
        if (request.getAssigneeId() != null) {
            wrapper.set(Task::getAssigneeId, request.getAssigneeId());
            hasUpdate = true;
        }
        if (request.getDueDate() != null) {
            wrapper.set(Task::getDueDate, request.getDueDate());
            hasUpdate = true;
        }

        if (hasUpdate) {
            taskMapper.update(null, wrapper);
        }
        return taskMapper.selectById(existing.getId());
    }

    private static boolean isTerminalStatus(String status) {
        return "done".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status);
    }
}
