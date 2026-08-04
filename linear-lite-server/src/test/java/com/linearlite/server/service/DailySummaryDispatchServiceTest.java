package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.ProjectEmailDispatch;
import com.linearlite.server.mapper.ProjectEmailDispatchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @Mock private DigestMailComposer composer;
    @Mock private DigestMailSender sender;
    @Mock private ProjectEmailDispatchMapper dispatchMapper;

    private DailySummaryDispatchService service;

    @BeforeEach
    void setUp() {
        service = new DailySummaryDispatchService(
                preferenceService, queryService,
                composer, sender, dispatchMapper);
    }

    @Test
    void skipsProjectWithoutDueTasks() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any(), any(), any())).thenReturn(List.of());

        service.dispatchForDate(date);

        verify(dispatchMapper, never()).insert(any(ProjectEmailDispatch.class));
        verify(sender, never()).send(anyString(), any());
    }

    @Test
    void sendsOneCrossProjectSummaryPerRecipient() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        DailySummaryTaskDto engineeringTask = assignedTask(1L, "ENG-1", 10L);
        DailySummaryTaskDto productTask = assignedTask(2L, "PROD-1", 20L);

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L, 20L));
        when(queryService.findDueTasks(eq(List.of(10L, 20L)), any(), any(), any(), any()))
                .thenReturn(List.of(engineeringTask, productTask));
        when(dispatchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dispatchMapper.update(any(), any())).thenReturn(1);
        when(composer.compose(eq("alice"), eq(date), any(LocalDateTime.class), any(LocalDateTime.class), any())).thenReturn(
                new DigestMailContent("主题", "<html/>", "text", 2));

        service.dispatchForDate(date);

        InOrder inOrder = org.mockito.Mockito.inOrder(composer, dispatchMapper, sender);
        inOrder.verify(composer).compose(eq("alice"), eq(date),
                eq(LocalDateTime.of(2026, 7, 23, 16, 30)),
                eq(LocalDateTime.of(2026, 7, 24, 16, 30)),
                org.mockito.ArgumentMatchers.argThat(tasks -> tasks.size() == 2));
        inOrder.verify(dispatchMapper).insert(any(ProjectEmailDispatch.class));
        inOrder.verify(sender).send(eq("a@example.com"), any(DigestMailContent.class));
        ArgumentCaptor<ProjectEmailDispatch> captor = ArgumentCaptor.forClass(ProjectEmailDispatch.class);
        verify(dispatchMapper, times(1)).insert(captor.capture());
        verify(dispatchMapper, times(1)).updateById(any(ProjectEmailDispatch.class));
        ProjectEmailDispatch recorded = captor.getValue();
        assertEquals("daily_summary", recorded.getScenarioKey());
        assertEquals("主题", recorded.getSubject());
        assertEquals(2, recorded.getTaskCount());
        assertEquals("sent", recorded.getStatus());
    }

    @Test
    void skipsWhenAnotherWorkerAlreadyClaimedDispatch() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        ProjectEmailDispatch existing = new ProjectEmailDispatch();
        existing.setId(99L);
        existing.setScenarioKey("daily_summary");
        existing.setBusinessDate(date);
        existing.setRecipientUserId(7L);
        existing.setStatus("pending");

        DailySummaryTaskDto task = assignedTask(1L, "ENG-1", 10L);

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any(), any(), any())).thenReturn(List.of(task));
        when(dispatchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(dispatchMapper.update(any(), any())).thenReturn(0);

        service.dispatchForDate(date);

        verify(composer).compose(eq("alice"), eq(date), any(LocalDateTime.class), any(LocalDateTime.class), any());
        verify(sender, never()).send(anyString(), any());
        verify(dispatchMapper, never()).updateById(any());
        verify(dispatchMapper, never()).insert(any());
    }

    @Test
    void skipsRecipientWithoutEmail() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setProjectId(10L);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail(null);

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any(), any(), any())).thenReturn(List.of(task));

        service.dispatchForDate(date);

        verify(sender, never()).send(anyString(), any());
        verify(dispatchMapper, never()).insert(any());
    }

    private DailySummaryTaskDto assignedTask(Long taskId, String taskKey, Long projectId) {
        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(taskId);
        task.setTaskKey(taskKey);
        task.setTitle("修复");
        task.setProjectId(projectId);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail("a@example.com");
        return task;
    }
}
