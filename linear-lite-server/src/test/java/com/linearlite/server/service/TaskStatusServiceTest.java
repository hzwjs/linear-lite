package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.TaskDependencyMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskStatusServiceTest {
    @Mock private TaskMapper taskMapper;
    @Mock private TaskActivityService taskActivityService;
    @Mock private TaskDependencyMapper taskDependencyMapper;
    @Mock private TaskExecutionAttemptMapper attemptMapper;

    @BeforeEach
    void initializeTableMetadata() {
        if (TableInfoHelper.getTableInfo(Task.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "task-status-test"), Task.class);
        }
    }

    @Test
    void duplicateIsTerminalAndUsesTheUnifiedStateWrite() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 28, 12, 0);
        Task existing = new Task();
        existing.setId(1L);
        existing.setStatus("todo");
        existing.setProgressPercent(30);
        Task updated = new Task();
        updated.setId(1L);
        updated.setStatus("duplicate");
        updated.setProgressPercent(30);
        updated.setCompletedAt(occurredAt);
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(existing);
        when(taskMapper.selectById(1L)).thenReturn(updated);

        new TaskStatusService(taskMapper, taskActivityService, taskDependencyMapper, attemptMapper)
                .updateState(1L, "duplicate", 30, 7L, occurredAt);

        ArgumentCaptor<UpdateWrapper<Task>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("completed_at"));
        verify(taskActivityService).recordFieldChangeAt(1L, 7L, "status", "todo", "duplicate", occurredAt);
    }

    @Test
    void zeroToPositiveProgressRecordsTheStoredBaselineChange() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 14, 17, 0);
        Task existing = new Task();
        existing.setId(2L);
        existing.setStatus("todo");
        existing.setProgressPercent(0);
        Task updated = new Task();
        updated.setId(2L);
        updated.setStatus("todo");
        updated.setProgressPercent(25);
        when(taskMapper.selectByIdForUpdate(2L)).thenReturn(existing);
        when(taskMapper.selectById(2L)).thenReturn(updated);

        new TaskStatusService(taskMapper, taskActivityService, taskDependencyMapper, attemptMapper)
                .updateState(2L, "todo", 25, 9L, occurredAt);

        verify(taskActivityService).recordFieldChangeAt(
                2L, 9L, "progressPercent", "0", "25", occurredAt);
    }

    @Test
    void completionEvidenceRequiresSuccessfulCurrentDeliveryAndStopConfirmation() {
        Task task = new Task(); task.setId(3L); task.setStatus("in_progress"); task.setProgressPercent(30);
        task.setInputRevision(2L);
        when(taskMapper.selectById(3L)).thenReturn(task);
        TaskExecutionAttempt old = new TaskExecutionAttempt(); old.setStatus("succeeded"); old.setInputRevision(1L);
        old.setStopConfirmedAt(LocalDateTime.now());
        old.setResultEvidence(new ObjectMapper().createObjectNode().put("checkpoint", "revision-one"));
        when(attemptMapper.selectByTaskId(3L)).thenReturn(List.of(old));

        var service = new TaskStatusService(taskMapper, taskActivityService, taskDependencyMapper, attemptMapper);
        assertEquals(false, service.hasCompletionEvidence(3L));
        old.setInputRevision(2L); old.setStopConfirmedAt(null);
        assertEquals(false, service.hasCompletionEvidence(3L));
        old.setStopConfirmedAt(LocalDateTime.now()); old.setStatus("failed");
        assertEquals(false, service.hasCompletionEvidence(3L));
        old.setStatus("succeeded"); old.setResultEvidence(new ObjectMapper().createObjectNode());
        assertEquals(false, service.hasCompletionEvidence(3L));
    }

    @Test
    void stoppedSuccessfulCurrentDeliveryProvidesCompletionEvidence() {
        Task task = new Task(); task.setId(4L); task.setStatus("in_progress"); task.setProgressPercent(40);
        task.setInputRevision(2L);
        TaskExecutionAttempt attempt = new TaskExecutionAttempt(); attempt.setInputRevision(2L);
        attempt.setStatus("succeeded"); attempt.setStopConfirmedAt(LocalDateTime.now());
        attempt.setResultEvidence(new ObjectMapper().createObjectNode().put("checkpoint", "result"));
        when(taskMapper.selectById(4L)).thenReturn(task);
        when(attemptMapper.selectByTaskId(4L)).thenReturn(List.of(attempt));
        var service = new TaskStatusService(taskMapper, taskActivityService, taskDependencyMapper, attemptMapper);
        assertTrue(service.hasCompletionEvidence(4L));
    }

    @Test
    void ordinaryTaskRetainsManualCompletion() {
        Task task = new Task(); task.setId(5L); task.setStatus("todo"); task.setProgressPercent(0);
        when(taskMapper.selectByIdForUpdate(5L)).thenReturn(task);
        when(taskMapper.selectById(5L)).thenReturn(task);
        var service = new TaskStatusService(taskMapper, taskActivityService, taskDependencyMapper, attemptMapper);
        assertEquals(task, service.updateState(5L, "done", 100, 9L, LocalDateTime.now()));
        verify(taskMapper).update(isNull(), any());
        verify(attemptMapper, never()).selectByTaskId(any());
    }
}
