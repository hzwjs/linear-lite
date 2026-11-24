package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskStatusServiceTest {
    @Mock private TaskMapper taskMapper;
    @Mock private TaskActivityService taskActivityService;

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

        new TaskStatusService(taskMapper, taskActivityService)
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

        new TaskStatusService(taskMapper, taskActivityService)
                .updateState(2L, "todo", 25, 9L, occurredAt);

        verify(taskActivityService).recordFieldChangeAt(
                2L, 9L, "progressPercent", "0", "25", occurredAt);
    }
}
