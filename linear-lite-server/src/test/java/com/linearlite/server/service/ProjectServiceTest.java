package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectInvitation;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ForbiddenOperationException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskFavoriteMapper taskFavoriteMapper;
    @Mock
    private TaskActivityMapper taskActivityMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ProjectInvitationMapper projectInvitationMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private EmailService emailService;
    @Mock
    private LabelService labelService;
    @Mock
    private TaskCommentMapper taskCommentMapper;
    @Mock
    private CommentMentionMapper commentMentionMapper;
    @Mock
    private InAppNotificationMapper inAppNotificationMapper;
    @Mock
    private ProjectEmailPreferenceService projectEmailPreferenceService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProjectAccessGuard projectAccessGuard;
    @Mock
    private ProjectLifecycleService projectLifecycleService;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectMapper,
                taskMapper,
                projectMemberMapper,
                projectInvitationMapper,
                userMapper,
                emailService,
                labelService,
                projectEmailPreferenceService,
                projectAccessGuard,
                projectLifecycleService
        );
    }

    @Test
    void createAddsCreatorAsOwnerMember() {
        Project saved = new Project();
        saved.setId(10L);
        saved.setName("Engineering");
        saved.setIdentifier("ENG");
        saved.setCreatorId(7L);

        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(10L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(10L)).thenReturn(saved);
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        Project project = projectService.create("Engineering", "eng", 7L);

        assertEquals(10L, project.getId());
        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(memberCaptor.capture());
        assertEquals(10L, memberCaptor.getValue().getProjectId());
        assertEquals(7L, memberCaptor.getValue().getUserId());
        assertEquals("owner", memberCaptor.getValue().getRole());
    }

    @Test
    void createInitializesEmailPreferenceForProject() {
        Project saved = new Project();
        saved.setId(10L);
        saved.setName("Engineering");
        saved.setIdentifier("ENG");
        saved.setCreatorId(7L);

        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(10L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(10L)).thenReturn(saved);
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        projectService.create("Engineering", "eng", 7L);

        verify(projectEmailPreferenceService).initializeForProject(10L);
    }

    @Test
    void listReturnsOnlyProjectsForCurrentMember() {
        Project project = new Project();
        project.setId(3L);
        project.setName("Engineering");
        project.setIdentifier("ENG");
        ProjectMember member = new ProjectMember();
        member.setProjectId(3L);
        member.setUserId(7L);

        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(member));
        when(projectMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(project));

        List<Project> result = projectService.list(7L);

        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getId());
    }

    @Test
    void listPreservesCurrentUserProjectSortOrder() {
        Project firstProject = new Project();
        firstProject.setId(1L);
        Project secondProject = new Project();
        secondProject.setId(2L);
        ProjectMember firstMembership = new ProjectMember();
        firstMembership.setId(11L);
        firstMembership.setProjectId(1L);
        firstMembership.setUserId(7L);
        firstMembership.setSortOrder(1);
        ProjectMember secondMembership = new ProjectMember();
        secondMembership.setId(12L);
        secondMembership.setProjectId(2L);
        secondMembership.setUserId(7L);
        secondMembership.setSortOrder(0);

        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(secondMembership, firstMembership));
        when(projectMapper.selectBatchIds(List.of(2L, 1L)))
                .thenReturn(List.of(firstProject, secondProject));

        List<Project> result = projectService.list(7L);

        assertEquals(List.of(2L, 1L), result.stream().map(Project::getId).toList());
    }

    @Test
    void reorderUpdatesEachMemberSortOrderInRequestedOrder() {
        ProjectMember first = new ProjectMember();
        first.setId(11L);
        first.setProjectId(1L);
        first.setUserId(7L);
        ProjectMember second = new ProjectMember();
        second.setId(12L);
        second.setProjectId(2L);
        second.setUserId(7L);
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));

        projectService.reorder(List.of(2L, 1L), 7L);

        assertEquals(0, second.getSortOrder());
        assertEquals(1, first.getSortOrder());
        verify(projectMemberMapper).updateById(second);
        verify(projectMemberMapper).updateById(first);
    }

    @Test
    void inviteUnknownEmailCreatesPendingInvitation() {
        Project project = new Project();
        project.setId(3L);
        project.setName("Engineering");
        project.setCreatorId(7L);

        when(projectMapper.selectById(3L)).thenReturn(project);
        when(projectInvitationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        projectService.invite(3L, 7L, "new@example.com");

        ArgumentCaptor<ProjectInvitation> invitationCaptor = ArgumentCaptor.forClass(ProjectInvitation.class);
        verify(projectInvitationMapper).insert(invitationCaptor.capture());
        verify(emailService).sendProjectInvitation("new@example.com", "Engineering");
        assertEquals("new@example.com", invitationCaptor.getValue().getEmail());
        assertEquals(7L, invitationCaptor.getValue().getInvitedBy());
    }

    @Test
    void inviteRejectsNonOwner() {
        Project project = new Project();
        project.setId(3L);
        project.setCreatorId(7L);

        when(projectMapper.selectById(3L)).thenReturn(project);

        assertThrows(ForbiddenOperationException.class, () -> projectService.invite(3L, 8L, "new@example.com"));
    }

    @Test
    void updateRejectsIdentifierChangeWhenProjectAlreadyHasTasks() {
        Project project = new Project();
        project.setId(3L);
        project.setIdentifier("ENG");
        project.setCreatorId(7L);
        when(projectMapper.selectById(3L)).thenReturn(project);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> projectService.update(3L, null, "PROD", 7L));

        assertEquals("项目已有任务，不能修改项目标识", error.getMessage());
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    @Test
    void updateAllowsIdentifierChangeWhenProjectHasNoTasks() {
        Project project = new Project();
        project.setId(3L);
        project.setIdentifier("ENG");
        project.setCreatorId(7L);
        when(projectMapper.selectById(3L)).thenReturn(project);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(projectMapper.selectById(3L)).thenReturn(project);

        Project updated = projectService.update(3L, null, "prod", 7L);

        assertEquals("PROD", updated.getIdentifier());
        verify(projectMapper).updateById(project);
    }

    @Test
    void deleteRemovesOwnedProjectAndRelatedTasks() {
        projectService.delete(3L, 7L);
        verify(projectLifecycleService).deleteProject(3L, 7L);
    }

    @Test
    void deleteRejectsNonOwner() {
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("只有项目创建者可以执行此操作"))
                .when(projectLifecycleService).deleteProject(3L, 8L);

        assertThrows(ForbiddenOperationException.class, () -> projectService.delete(3L, 8L));
    }
}
