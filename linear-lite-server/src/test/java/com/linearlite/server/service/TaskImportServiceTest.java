package com.linearlite.server.service;

import com.linearlite.server.dto.TaskImportRequest;
import com.linearlite.server.dto.TaskImportResponse;
import com.linearlite.server.dto.TaskImportRowRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectTaskSeq;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskImportServiceTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskPermissionGuard taskPermissionGuard;
    @Mock
    private TaskSequenceService taskSequenceService;
    @Mock
    private TaskActivityService taskActivityService;

    private TaskImportService taskImportService;

    @BeforeEach
    void setUp() {
        taskImportService = new TaskImportService(
                projectMapper,
                taskMapper,
                taskPermissionGuard,
                taskSequenceService,
                taskActivityService
        );
    }

    @Test
    void importTasksCreatesRowsAndConnectsParentChildRelations() {
        Project project = new Project();
        project.setId(1L);
        project.setIdentifier("ENG");

        TaskImportRowRequest parentRow = new TaskImportRowRequest();
        parentRow.setImportId("A-1");
        parentRow.setTitle("Parent");
        parentRow.setStatus("todo");
        parentRow.setPriority("high");
        parentRow.setAssigneeId(1L);

        TaskImportRowRequest childRow = new TaskImportRowRequest();
        childRow.setImportId("A-2");
        childRow.setParentImportId("A-1");
        childRow.setTitle("Child");
        childRow.setStatus("in_progress");
        childRow.setPriority("medium");
        childRow.setAssigneeId(2L);

        TaskImportRequest request = new TaskImportRequest();
        request.setProjectId(1L);
        request.setRows(List.of(parentRow, childRow));

        when(projectMapper.selectById(1L)).thenReturn(project);
        when(taskSequenceService.reserveTaskNumbers(1L, "ENG", 2)).thenReturn(1L);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId("Parent".equals(task.getTitle()) ? 101L : 102L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        TaskImportResponse response = taskImportService.importTasks(request, 7L);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(taskCaptor.capture());
        List<Task> inserted = taskCaptor.getAllValues();
        assertEquals("ENG-1", inserted.get(0).getTaskKey());
        assertEquals("ENG-2", inserted.get(1).getTaskKey());
        assertEquals(1, response.getParentCount());
        assertEquals(1, response.getSubtaskCount());
        assertEquals(2, response.getCreatedCount());
        assertEquals(null, inserted.get(0).getAssigneeDisplayName());
        assertEquals(null, inserted.get(1).getAssigneeDisplayName());
        verify(taskMapper).updateById(org.mockito.ArgumentMatchers.argThat(task ->
                task.getId().equals(102L) && task.getParentId().equals(101L)
        ));
        verify(taskPermissionGuard).requireProjectMember(1L, 7L);
    }

    @Test
    void importTasksStoresAssigneeDisplayNameWhenNoAssigneeId() {
        Project project = new Project();
        project.setId(1L);
        project.setIdentifier("ENG");

        TaskImportRowRequest row = new TaskImportRowRequest();
        row.setImportId("X-1");
        row.setTitle("External assignee");
        row.setAssigneeId(null);
        row.setAssigneeDisplayName("  外部处理人  ");

        TaskImportRequest request = new TaskImportRequest();
        request.setProjectId(1L);
        request.setRows(List.of(row));

        when(projectMapper.selectById(1L)).thenReturn(project);
        when(taskSequenceService.reserveTaskNumbers(1L, "ENG", 1)).thenReturn(1L);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(201L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        taskImportService.importTasks(request, 7L);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(taskCaptor.capture());
        Task captured = taskCaptor.getValue();
        assertEquals(null, captured.getAssigneeId());
        assertEquals("外部处理人", captured.getAssigneeDisplayName());
    }

    @Test
    void importTasksRejectsDuplicateImportIds() {
        TaskImportRowRequest row1 = new TaskImportRowRequest();
        row1.setImportId("A-1");
        row1.setTitle("Parent");

        TaskImportRowRequest row2 = new TaskImportRowRequest();
        row2.setImportId("A-1");
        row2.setTitle("Duplicate");

        TaskImportRequest request = new TaskImportRequest();
        request.setProjectId(1L);
        request.setRows(List.of(row1, row2));

        Project project = new Project();
        project.setId(1L);
        project.setIdentifier("ENG");
        when(projectMapper.selectById(1L)).thenReturn(project);

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> taskImportService.importTasks(request, 7L)
        );

        assertEquals("Import ID must be unique within the file: A-1", error.getMessage());
        verify(taskMapper, never()).insert(any());
    }
}
