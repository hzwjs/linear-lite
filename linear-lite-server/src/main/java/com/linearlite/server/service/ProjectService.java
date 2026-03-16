package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目业务：列表、创建。
 */
@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final TaskFavoriteMapper taskFavoriteMapper;
    private final TaskActivityMapper taskActivityMapper;

    public ProjectService(
            ProjectMapper projectMapper,
            TaskMapper taskMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            TaskActivityMapper taskActivityMapper) {
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.taskFavoriteMapper = taskFavoriteMapper;
        this.taskActivityMapper = taskActivityMapper;
    }

    /**
     * 返回全部项目列表（侧边栏用）。
     */
    public List<Project> list() {
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>().orderByAsc(Project::getId));
    }

    /**
     * 创建项目。name、identifier 必填；identifier 需唯一。
     */
    public Project create(String name, String identifier, Long creatorId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("项目标识不能为空");
        }
        if (creatorId == null) {
            throw new IllegalArgumentException("项目创建者不能为空");
        }
        String trimmedId = identifier.trim().toUpperCase();
        Long existing = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getIdentifier, trimmedId));
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("项目标识已存在: " + trimmedId);
        }
        Project project = new Project();
        project.setName(name.trim());
        project.setIdentifier(trimmedId);
        project.setCreatorId(creatorId);
        projectMapper.insert(project);
        return projectMapper.selectById(project.getId());
    }

    /**
     * 更新项目。仅更新非空字段；identifier 需唯一（排除自身）。
     */
    public Project update(Long id, String name, String identifier) {
        Project existing = projectMapper.selectById(id);
        if (existing == null) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        if (name != null && !name.isBlank()) {
            existing.setName(name.trim());
        }
        if (identifier != null && !identifier.isBlank()) {
            String trimmedId = identifier.trim().toUpperCase();
            if (!trimmedId.equals(existing.getIdentifier())) {
                Long count = projectMapper.selectCount(
                        new LambdaQueryWrapper<Project>()
                                .eq(Project::getIdentifier, trimmedId)
                                .ne(Project::getId, id));
                if (count != null && count > 0) {
                    throw new IllegalArgumentException("项目标识已存在: " + trimmedId);
                }
                existing.setIdentifier(trimmedId);
            }
        }
        projectMapper.updateById(existing);
        return projectMapper.selectById(id);
    }

    public void delete(Long id, Long currentUserId) {
        Project existing = projectMapper.selectById(id);
        if (existing == null) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        if (currentUserId == null || !currentUserId.equals(existing.getCreatorId())) {
            throw new ForbiddenOperationException("只有项目创建者可以删除项目");
        }

        List<Task> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>().eq(Task::getProjectId, id));
        List<Long> taskIds = tasks.stream()
                .map(Task::getId)
                .collect(Collectors.toList());

        if (!taskIds.isEmpty()) {
            taskActivityMapper.delete(
                    new LambdaQueryWrapper<com.linearlite.server.entity.TaskActivity>()
                            .in(com.linearlite.server.entity.TaskActivity::getTaskId, taskIds));
            taskFavoriteMapper.delete(
                    new LambdaQueryWrapper<com.linearlite.server.entity.TaskFavorite>()
                            .in(com.linearlite.server.entity.TaskFavorite::getTaskId, taskIds));
        }

        taskMapper.delete(new LambdaQueryWrapper<Task>().eq(Task::getProjectId, id));
        projectMapper.deleteById(id);
    }
}
