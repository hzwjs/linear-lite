package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.CommentMention;
import com.linearlite.server.entity.InAppNotification;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectInvitation;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.CommentMentionMapper;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.ProjectInvitationMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import com.linearlite.server.entity.User;
import com.linearlite.server.dto.UserSummaryDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectInvitationMapper projectInvitationMapper;
    private final UserMapper userMapper;
    private final EmailService emailService;
    private final LabelService labelService;
    private final TaskCommentMapper taskCommentMapper;
    private final CommentMentionMapper commentMentionMapper;
    private final InAppNotificationMapper inAppNotificationMapper;

    public ProjectService(
            ProjectMapper projectMapper,
            TaskMapper taskMapper,
            TaskFavoriteMapper taskFavoriteMapper,
            TaskActivityMapper taskActivityMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectInvitationMapper projectInvitationMapper,
            UserMapper userMapper,
            EmailService emailService,
            LabelService labelService,
            TaskCommentMapper taskCommentMapper,
            CommentMentionMapper commentMentionMapper,
            InAppNotificationMapper inAppNotificationMapper) {
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.taskFavoriteMapper = taskFavoriteMapper;
        this.taskActivityMapper = taskActivityMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectInvitationMapper = projectInvitationMapper;
        this.userMapper = userMapper;
        this.emailService = emailService;
        this.labelService = labelService;
        this.taskCommentMapper = taskCommentMapper;
        this.commentMentionMapper = commentMentionMapper;
        this.inAppNotificationMapper = inAppNotificationMapper;
    }

    /**
     * 返回全部项目列表（侧边栏用）。
     */
    public List<Project> list(Long currentUserId) {
        requireMemberUserId(currentUserId);
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>()
                        .inSql(Project::getId,
                                "SELECT project_id FROM project_members WHERE user_id = " + currentUserId)
                        .orderByAsc(Project::getId));
    }

    /**
     * 返回项目成员列表（负责人选择用）。
     */
    public List<UserSummaryDto> listMembers(Long projectId, Long currentUserId) {
        requireProjectMember(projectId, currentUserId);
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .inSql(User::getId,
                                "SELECT user_id FROM project_members WHERE project_id = " + projectId)
                        .orderByAsc(User::getUsername));
        return users.stream()
                .map(u -> new UserSummaryDto(u.getId(), u.getUsername(), u.getAvatarUrl()))
                .collect(Collectors.toList());
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
        addMember(project.getId(), creatorId, "owner");
        return projectMapper.selectById(project.getId());
    }

    /**
     * 更新项目。仅更新非空字段；identifier 需唯一（排除自身）。
     */
    public Project update(Long id, String name, String identifier, Long currentUserId) {
        Project existing = projectMapper.selectById(id);
        if (existing == null) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        requireProjectMember(id, currentUserId);
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

    public void invite(Long projectId, Long currentUserId, String email) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + projectId);
        }
        requireProjectMember(projectId, currentUserId);
        if (!currentUserId.equals(project.getCreatorId())) {
            throw new ForbiddenOperationException("只有项目创建者可以邀请成员");
        }
        String normalizedEmail = requireEmail(email);

        Long memberExists = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .inSql(ProjectMember::getUserId,
                                "SELECT id FROM users WHERE email = '" + normalizedEmail + "'")
        );
        if (memberExists != null && memberExists > 0) {
            throw new IllegalArgumentException("该邮箱已在项目中");
        }

        Long invitationExists = projectInvitationMapper.selectCount(
                new LambdaQueryWrapper<ProjectInvitation>()
                        .eq(ProjectInvitation::getProjectId, projectId)
                        .eq(ProjectInvitation::getEmail, normalizedEmail)
                        .isNull(ProjectInvitation::getAcceptedAt)
        );
        if (invitationExists != null && invitationExists > 0) {
            throw new IllegalArgumentException("该邮箱已被邀请");
        }

        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setProjectId(projectId);
        invitation.setEmail(normalizedEmail);
        invitation.setInvitedBy(currentUserId);
        invitation.setCreatedAt(LocalDateTime.now());
        projectInvitationMapper.insert(invitation);
        emailService.sendProjectInvitation(normalizedEmail, project.getName());
    }

    public void delete(Long id, Long currentUserId) {
        Project existing = projectMapper.selectById(id);
        if (existing == null) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        requireProjectMember(id, currentUserId);
        if (currentUserId == null || !currentUserId.equals(existing.getCreatorId())) {
            throw new ForbiddenOperationException("只有项目创建者可以删除项目");
        }

        List<Task> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>().eq(Task::getProjectId, id));
        List<Long> taskIds = tasks.stream()
                .map(Task::getId)
                .collect(Collectors.toList());

        if (!taskIds.isEmpty()) {
            inAppNotificationMapper.delete(
                    new LambdaQueryWrapper<InAppNotification>().in(InAppNotification::getTaskId, taskIds));
            List<TaskComment> comments = taskCommentMapper.selectList(
                    new LambdaQueryWrapper<TaskComment>().in(TaskComment::getTaskId, taskIds));
            List<Long> commentIds = comments.stream().map(TaskComment::getId).collect(Collectors.toList());
            if (!commentIds.isEmpty()) {
                commentMentionMapper.delete(
                        new LambdaQueryWrapper<CommentMention>().in(CommentMention::getCommentId, commentIds));
            }
            taskCommentMapper.delete(new LambdaQueryWrapper<TaskComment>().in(TaskComment::getTaskId, taskIds));
            labelService.deleteLinksForTaskIds(taskIds);
            taskActivityMapper.delete(
                    new LambdaQueryWrapper<com.linearlite.server.entity.TaskActivity>()
                            .in(com.linearlite.server.entity.TaskActivity::getTaskId, taskIds));
            taskFavoriteMapper.delete(
                    new LambdaQueryWrapper<com.linearlite.server.entity.TaskFavorite>()
                            .in(com.linearlite.server.entity.TaskFavorite::getTaskId, taskIds));
        }

        taskMapper.delete(new LambdaQueryWrapper<Task>().eq(Task::getProjectId, id));
        projectInvitationMapper.delete(new LambdaQueryWrapper<ProjectInvitation>().eq(ProjectInvitation::getProjectId, id));
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, id));
        projectMapper.deleteById(id);
    }

    public void requireProjectMember(Long projectId, Long userId) {
        requireMemberUserId(userId);
        Long count = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId)
        );
        if (count == null || count == 0) {
            throw new ForbiddenOperationException("你不是该项目成员");
        }
    }

    private void addMember(Long projectId, Long userId, String role) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        member.setCreatedAt(LocalDateTime.now());
        projectMemberMapper.insert(member);
    }

    private void requireMemberUserId(Long currentUserId) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
    }

    private String requireEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return email.trim().toLowerCase();
    }
}
