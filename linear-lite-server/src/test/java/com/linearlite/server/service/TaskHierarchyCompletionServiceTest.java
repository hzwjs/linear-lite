package com.linearlite.server.service;

import com.linearlite.server.dto.DirectChildCompletion;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskHierarchyCompletionServiceTest {
    @Mock private TaskMapper taskMapper;
    @Mock private TaskStatusService taskStatusService;

    @Test
    void completesAndCascadesWhenDoneCanceledAndDuplicateExhaustDirectChildren() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 28, 12, 0);
        Task child = task(3L, "ENG-3", "duplicate", 2L);
        Task parent = task(2L, "ENG-2", "in_progress", 1L);
        Task root = task(1L, "ENG-1", "todo", null);
        Task completedParent = task(2L, "ENG-2", "done", 1L);
        completedParent.setProgressPercent(100);
        completedParent.setCompletedAt(occurredAt);
        Task completedRoot = task(1L, "ENG-1", "done", null);
        completedRoot.setProgressPercent(100);
        completedRoot.setCompletedAt(occurredAt);

        when(taskMapper.selectById(3L)).thenReturn(child);
        when(taskMapper.selectByIdForUpdate(2L)).thenReturn(parent);
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(root);
        when(taskMapper.selectDirectChildCompletion(2L)).thenReturn(completion(3, 3));
        when(taskMapper.selectDirectChildCompletion(1L)).thenReturn(completion(1, 1));
        when(taskStatusService.updateState(2L, "done", 100, 7L, occurredAt)).thenReturn(completedParent);
        when(taskStatusService.updateState(1L, "done", 100, 7L, occurredAt)).thenReturn(completedRoot);

        var changes = new TaskHierarchyCompletionService(taskMapper, taskStatusService)
                .completeEligibleAncestors(3L, 7L, occurredAt);

        assertEquals(java.util.List.of("ENG-2", "ENG-1"), changes.stream().map(c -> c.taskKey()).toList());
    }

    @Test
    void doesNotOverwriteCanceledParent() {
        Task parent = task(2L, "ENG-2", "canceled", 1L);
        when(taskMapper.selectByIdForUpdate(2L)).thenReturn(parent);
        when(taskMapper.selectDirectChildCompletion(2L)).thenReturn(completion(2, 2));

        var changes = new TaskHierarchyCompletionService(taskMapper, taskStatusService)
                .completeEligibleParentChain(2L, 7L, LocalDateTime.now());

        assertEquals(0, changes.size());
        verify(taskStatusService, never()).updateState(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotCompleteParentWhileAnyDirectChildIsOpen() {
        Task parent = task(2L, "ENG-2", "in_progress", 1L);
        when(taskMapper.selectByIdForUpdate(2L)).thenReturn(parent);
        when(taskMapper.selectDirectChildCompletion(2L)).thenReturn(completion(3, 2));

        var changes = new TaskHierarchyCompletionService(taskMapper, taskStatusService)
                .completeEligibleParentChain(2L, 7L, LocalDateTime.now());

        assertEquals(0, changes.size());
        verify(taskStatusService, never()).updateState(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void parentWithoutChildrenIsNotAutoCompleted() {
        Task parent = task(2L, "ENG-2", "todo", null);
        when(taskMapper.selectByIdForUpdate(2L)).thenReturn(parent);
        when(taskMapper.selectDirectChildCompletion(2L)).thenReturn(completion(0, 0));

        var changes = new TaskHierarchyCompletionService(taskMapper, taskStatusService)
                .completeEligibleParentChain(2L, 7L, LocalDateTime.now());

        assertEquals(0, changes.size());
        verify(taskStatusService, never()).updateState(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    private static Task task(Long id, String key, String status, Long parentId) {
        Task task = new Task();
        task.setId(id);
        task.setTaskKey(key);
        task.setStatus(status);
        task.setParentId(parentId);
        task.setProgressPercent(0);
        return task;
    }

    private static DirectChildCompletion completion(long total, long terminal) {
        DirectChildCompletion completion = new DirectChildCompletion();
        completion.setTotalCount(total);
        completion.setTerminalCount(terminal);
        return completion;
    }
}
