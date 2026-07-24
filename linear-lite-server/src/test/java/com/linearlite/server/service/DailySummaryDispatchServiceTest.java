package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectEmailDispatch;
import com.linearlite.server.mapper.ProjectEmailDispatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailySummaryDispatchServiceTest {

    @Mock private ProjectEmailPreferenceService preferenceService;
    @Mock private DailySummaryQueryService queryService;
    @Mock private ProjectMapper projectMapper;
    @Mock private DigestMailComposer composer;
    @Mock private DigestMailSender sender;
    @Mock private ProjectEmailDispatchMapper dispatchMapper;

    private DailySummaryDispatchService service;

    @BeforeEach
    void setUp() {
        service = new DailySummaryDispatchService(
                preferenceService, queryService, projectMapper,
                composer, sender, dispatchMapper);
    }

    @Test
    void skipsProjectWithoutDueTasks() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of());

        service.dispatchForDate(date);

        verify(dispatchMapper, never()).insert(any(ProjectEmailDispatch.class));
        verify(sender, never()).send(anyString(), any());
    }

    @Test
    void sendsAndRecordsPerRecipient() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("修复");
        task.setProjectId(10L);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail("a@example.com");

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of(task));
        when(dispatchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dispatchMapper.update(any(), any())).thenReturn(1);
        when(composer.compose(eq(project), eq("alice"), eq(date), any())).thenReturn(
                new DigestMailContent("主题", "<html/>", "text", 1));

        service.dispatchForDate(date);

        verify(sender).send(eq("a@example.com"), any(DigestMailContent.class));
        ArgumentCaptor<ProjectEmailDispatch> captor = ArgumentCaptor.forClass(ProjectEmailDispatch.class);
        verify(dispatchMapper, times(1)).insert(captor.capture());
        verify(dispatchMapper, times(1)).updateById(any(ProjectEmailDispatch.class));
        ProjectEmailDispatch recorded = captor.getValue();
        assertEquals("daily_summary", recorded.getScenarioKey());
        assertEquals("sent", recorded.getStatus());
    }

    @Test
    void skipsWhenAnotherWorkerAlreadyClaimedDispatch() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");

        ProjectEmailDispatch existing = new ProjectEmailDispatch();
        existing.setId(99L);
        existing.setProjectId(10L);
        existing.setScenarioKey("daily_summary");
        existing.setBusinessDate(date);
        existing.setRecipientUserId(7L);
        existing.setStatus("pending");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("修复");
        task.setProjectId(10L);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail("a@example.com");

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of(task));
        when(dispatchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(dispatchMapper.update(any(), any())).thenReturn(0);

        service.dispatchForDate(date);

        verify(composer, never()).compose(any(), any(), any(), any());
        verify(sender, never()).send(anyString(), any());
        verify(dispatchMapper, never()).updateById(any());
    }

    @Test
    void skipsRecipientWithoutEmail() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setProjectId(10L);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail(null);

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of(task));

        service.dispatchForDate(date);

        verify(sender, never()).send(anyString(), any());
        verify(dispatchMapper, never()).insert(any());
    }
}
