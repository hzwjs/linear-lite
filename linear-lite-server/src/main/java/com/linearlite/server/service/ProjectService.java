package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Project;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 项目业务：列表、创建。
 */
@Service
public class ProjectService {

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
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
    public Project create(String name, String identifier) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("项目标识不能为空");
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
}
