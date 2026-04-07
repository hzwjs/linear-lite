package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectInvitation;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectInvitationMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
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

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectMapper,
                taskMapper,
                taskFavoriteMapper,
                taskActivityMapper,
                projectMemberMapper,
                projectInvitationMapper,
                userMapper,
                emailService,
                labelService
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
    void listReturnsOnlyProjectsForCurrentMember() {
        Project project = new Project();
        project.setId(3L);
        project.setName("Engineering");
        project.setIdentifier("ENG");

        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(project));

        List<Project> result = projectService.list(7L);

        assertEquals(1, result.size());
        assertEquals(3L, result.get(0).getId());
    }

    @Test
    void inviteUnknownEmailCreatesPendingInvitation() {
        Project project = new Project();
        project.setId(3L);
        project.setName("Engineering");
        project.setCreatorId(7L);

        when(projectMapper.selectById(3L)).thenReturn(project);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L, 0L);
        when(projectInvitationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

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
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(ForbiddenOperationException.class, () -> projectService.invite(3L, 8L, "new@example.com"));
    }

    @Test
    void deleteRemovesOwnedProjectAndRelatedTasks() {
        Project project = new Project();
        project.setId(3L);
        project.setCreatorId(7L);

        Task task = new Task();
        task.setId(11L);
        task.setProjectId(3L);

        when(projectMapper.selectById(3L)).thenReturn(project);
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        projectService.delete(3L, 7L);

        verify(labelService).deleteLinksForTaskIds(List.of(11L));
        verify(taskActivityMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskFavoriteMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectInvitationMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectMemberMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectMapper).deleteById(3L);
    }

    @Test
    void deleteRejectsNonOwner() {
        Project project = new Project();
        project.setId(3L);
        project.setCreatorId(7L);

        when(projectMapper.selectById(3L)).thenReturn(project);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThrows(ForbiddenOperationException.class, () -> projectService.delete(3L, 8L));
    }
}
