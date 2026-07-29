package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccessGuard {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectAccessGuard(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public void requireMember(Long projectId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        Long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new ForbiddenOperationException("你不是该项目成员");
        }
    }

    public void requireOwner(Long projectId, Long userId) {
        requireMember(projectId, userId);
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + projectId);
        }
        if (!userId.equals(project.getCreatorId())) {
            throw new ForbiddenOperationException("只有项目创建者可以执行此操作");
        }
    }
}
