package com.linearlite.server.service;

import com.linearlite.server.dto.TaskImportRequest;
import com.linearlite.server.dto.TaskImportResponse;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private TaskCommandService taskCommandService;
    @Mock
    private TaskImportService taskImportService;

    @Test
    void facadeDelegatesToSplitServices() {
        TaskService service = new TaskService(taskQueryService, taskCommandService, taskImportService);
        Task task = new Task();
        TaskImportRequest importRequest = new TaskImportRequest();
        TaskImportResponse importResponse = new TaskImportResponse();
        UpdateTaskRequest updateTaskRequest = new UpdateTaskRequest();
        List<Task> tasks = List.of(task);

        when(taskQueryService.listByProjectId(1L, true, null, 9L)).thenReturn(tasks);
        when(taskCommandService.update("ENG-1", updateTaskRequest, 9L)).thenReturn(task);
        when(taskImportService.importTasks(importRequest, 9L)).thenReturn(importResponse);

        assertEquals(tasks, service.listByProjectId(1L, true, null, 9L));
        assertEquals(task, service.update("ENG-1", updateTaskRequest, 9L));
        assertEquals(importResponse, service.importTasks(importRequest, 9L));

        verify(taskQueryService).listByProjectId(1L, true, null, 9L);
        verify(taskCommandService).update("ENG-1", updateTaskRequest, 9L);
        verify(taskImportService).importTasks(importRequest, 9L);
    }

    @Test
    void progressStatusLinkageHundredPercentFromOpenBecomesDone() {
        Task existing = new Task();
        existing.setStatus("todo");
        existing.setProgressPercent(20);
        TaskService.ResolvedStatusProgress r =
                TaskService.resolveStatusProgressLinkage(existing, false, true, "todo", 100);
        assertEquals("done", r.status);
        assertEquals(100, r.progressPercent);
    }

    @Test
    void progressStatusLinkageLoweringProgressFromDoneReopens() {
        Task existing = new Task();
        existing.setStatus("done");
        existing.setProgressPercent(100);
        TaskService.ResolvedStatusProgress r =
                TaskService.resolveStatusProgressLinkage(existing, false, true, "done", 40);
        assertEquals("in_progress", r.status);
        assertEquals(40, r.progressPercent);
    }

    @Test
    void progressStatusLinkageLoweringProgressFromDoneReopensWhenStatusAlsoSent() {
        Task existing = new Task();
        existing.setStatus("done");
        existing.setProgressPercent(100);
        TaskService.ResolvedStatusProgress r =
                TaskService.resolveStatusProgressLinkage(existing, true, true, "done", 47);
        assertEquals("in_progress", r.status);
        assertEquals(47, r.progressPercent);
    }

    @Test
    void progressStatusLinkageExplicitDoneRaisesProgress() {
        Task existing = new Task();
        existing.setStatus("todo");
        existing.setProgressPercent(10);
        TaskService.ResolvedStatusProgress r =
                TaskService.resolveStatusProgressLinkage(existing, true, false, "done", 10);
        assertEquals("done", r.status);
        assertEquals(100, r.progressPercent);
    }

    @Test
    void progressStatusLinkageReopenFromDoneClampsHundredToNinetyNine() {
        Task existing = new Task();
        existing.setStatus("done");
        existing.setProgressPercent(100);
        TaskService.ResolvedStatusProgress r =
                TaskService.resolveStatusProgressLinkage(existing, true, false, "todo", 100);
        assertEquals("todo", r.status);
        assertEquals(99, r.progressPercent);
    }
}
