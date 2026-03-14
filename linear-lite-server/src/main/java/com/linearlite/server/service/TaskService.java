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

    public TaskService(TaskMapper taskMapper, ProjectMapper projectMapper) {
        this.taskMapper = taskMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 按项目 ID 返回任务列表，支持仅顶层或按父任务过滤；按 id 升序。返回前填充 subIssueCount、completedSubIssueCount。
     *
     * @param projectId    必填
     * @param topLevelOnly  true 时仅返回 parent_id IS NULL
     * @param parentId      非空时仅返回该父任务下的直接子任务（与 topLevelOnly 互斥，优先 parentId）
     */
    public List<Task> listByProjectId(Long projectId, Boolean topLevelOnly, Long parentId) {
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
        fillSubIssueCounts(Collections.singletonList(inserted));
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
            String desc = request.getDescription().trim();
            wrapper.set(Task::getDescription, desc.isEmpty() ? null : desc);
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
        if (Boolean.TRUE.equals(request.getClearParent())) {
            wrapper.set(Task::getParentId, null);
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
            wrapper.set(Task::getParentId, newParentId);
            hasUpdate = true;
        }

        if (hasUpdate) {
            taskMapper.update(null, wrapper);
        }
        Task updated = taskMapper.selectById(existing.getId());
        fillSubIssueCounts(Collections.singletonList(updated));
        return updated;
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
}
