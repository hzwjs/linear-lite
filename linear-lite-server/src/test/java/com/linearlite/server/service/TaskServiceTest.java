package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskImportRequest;
import com.linearlite.server.dto.TaskImportResponse;
import com.linearlite.server.dto.TaskImportRowRequest;
import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectTaskSeqMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private TaskFavoriteMapper taskFavoriteMapper;
    @Mock
    private TaskActivityService taskActivityService;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ProjectTaskSeqMapper projectTaskSeqMapper;
    @Mock
    private LabelService labelService;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskMapper,
                projectMapper,
                taskFavoriteMapper,
                taskActivityService,
                projectMemberMapper,
                projectTaskSeqMapper,
                labelService
        );
    }

    @Test
    void listByProjectIdRejectsNonMember() {
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        Assertions.assertThrows(
                ForbiddenOperationException.class,
                () -> taskService.listByProjectId(1L, null, null, 7L)
        );
    }

    @Test
    void listByProjectIdMarksFavoritedTasksForCurrentUser() {
        Task task = new Task();
        task.setId(11L);
        task.setTaskKey("ENG-11");
        task.setProjectId(1L);

        TaskFavorite favorite = new TaskFavorite();
        favorite.setTaskId(11L);
        favorite.setUserId(7L);

        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task), List.of());
        when(taskFavoriteMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(favorite));
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        List<Task> result = taskService.listByProjectId(1L, null, null, 7L);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getFavorited());
    }

    @Test
    void addFavoriteCreatesRelationOnlyOnce() {
        Task task = new Task();
        task.setId(12L);
        task.setTaskKey("ENG-12");
        task.setProjectId(1L);

        TaskFavorite favorite = new TaskFavorite();
        favorite.setTaskId(12L);
        favorite.setUserId(9L);

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(taskFavoriteMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskFavoriteMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(favorite));
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        Task result = taskService.addFavorite("ENG-12", 9L);

        ArgumentCaptor<TaskFavorite> captor = ArgumentCaptor.forClass(TaskFavorite.class);
        verify(taskFavoriteMapper).insert(captor.capture());
        verify(taskActivityService).recordAction(12L, 9L, "favorited");
        assertEquals(12L, captor.getValue().getTaskId());
        assertEquals(9L, captor.getValue().getUserId());
        assertTrue(result.getFavorited());
    }

    @Test
    void removeFavoriteDeletesExistingRelation() {
        Task task = new Task();
        task.setId(13L);
        task.setTaskKey("ENG-13");
        task.setProjectId(1L);

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(taskMapper.selectById(13L)).thenReturn(task);
        when(taskFavoriteMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        Task result = taskService.removeFavorite("ENG-13", 9L);

        verify(taskFavoriteMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskActivityService).recordAction(13L, 9L, "unfavorited");
        verify(taskFavoriteMapper, never()).insert(any());
        assertEquals(Boolean.FALSE, result.getFavorited());
    }

    @Test
    void updateRecordsActivityForChangedFieldsOnly() {
        Task existing = new Task();
        existing.setId(21L);
        existing.setTaskKey("ENG-21");
        existing.setTitle("Old title");
        existing.setDescription("Old desc");
        existing.setStatus("todo");
        existing.setPriority("medium");
        existing.setAssigneeId(1L);
        existing.setDueDate(null);
        existing.setProjectId(1L);

        Task updated = new Task();
        updated.setId(21L);
        updated.setTaskKey("ENG-21");
        updated.setTitle("New title");
        updated.setDescription("Old desc");
        updated.setStatus("in_progress");
        updated.setPriority("high");
        updated.setAssigneeId(2L);
        updated.setDueDate(null);
        updated.setProjectId(1L);

        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle("New title");
        request.setStatus("in_progress");
        request.setPriority("high");
        request.setAssigneeId(2L);

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(21L)).thenReturn(updated);
        when(taskFavoriteMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        taskService.update("ENG-21", request, 7L);

        verify(taskActivityService).recordFieldChange(21L, 7L, "title", "Old title", "New title");
        verify(taskActivityService).recordDescriptionChange(21L, 7L, "Old desc", "Old desc");
        verify(taskActivityService).recordFieldChange(21L, 7L, "status", "todo", "in_progress");
        verify(taskActivityService).recordFieldChange(21L, 7L, "priority", "medium", "high");
        verify(taskActivityService).recordAssigneeChange(21L, 7L, 1L, 2L);
    }

    @Test
    void updateDoesNotRecordActivityWhenPayloadDoesNotChangeTask() {
        Task existing = new Task();
        existing.setId(22L);
        existing.setTaskKey("ENG-22");
        existing.setTitle("Same");
        existing.setStatus("todo");
        existing.setPriority("medium");
        existing.setProjectId(1L);

        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle("Same");

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(22L)).thenReturn(existing);
        when(taskFavoriteMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        taskService.update("ENG-22", request, 7L);

        verify(taskActivityService, never()).recordFieldChange(any(), any(), any(), any(), any());
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

        Task insertedParent = new Task();
        insertedParent.setId(101L);
        insertedParent.setTaskKey("ENG-1");
        insertedParent.setProjectId(1L);

        Task insertedChild = new Task();
        insertedChild.setId(102L);
        insertedChild.setTaskKey("ENG-2");
        insertedChild.setProjectId(1L);
        com.linearlite.server.entity.ProjectTaskSeq seq = new com.linearlite.server.entity.ProjectTaskSeq();
        seq.setProjectId(1L);
        seq.setNextNumber(1L);

        when(projectMapper.selectById(1L)).thenReturn(project);
        when(projectTaskSeqMapper.selectByProjectIdForUpdate(1L)).thenReturn(seq);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId("Parent".equals(task.getTitle()) ? 101L : 102L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));
        when(projectTaskSeqMapper.updateNextNumber(1L, 3L)).thenReturn(1);

        TaskImportResponse response = taskService.importTasks(request, 7L);

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

        Task inserted = new Task();
        inserted.setId(201L);
        inserted.setTaskKey("ENG-1");
        inserted.setProjectId(1L);
        com.linearlite.server.entity.ProjectTaskSeq seq = new com.linearlite.server.entity.ProjectTaskSeq();
        seq.setProjectId(1L);
        seq.setNextNumber(1L);

        when(projectMapper.selectById(1L)).thenReturn(project);
        when(projectTaskSeqMapper.selectByProjectIdForUpdate(1L)).thenReturn(seq);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(201L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));
        when(projectTaskSeqMapper.updateNextNumber(1L, 2L)).thenReturn(1);

        taskService.importTasks(request, 7L);

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
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> taskService.importTasks(request, 7L)
        );

        assertEquals("Import ID must be unique within the file: A-1", error.getMessage());
        verify(taskMapper, never()).insert(any());
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

    /** 与 TaskEditor 自动保存一致：PUT 同时携带 status 与 progressPercent */
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

    @Test
    void updateWithLabelsCallsReplaceAndRecordsActivity() {
        Task existing = new Task();
        existing.setId(30L);
        existing.setTaskKey("ENG-30");
        existing.setTitle("T");
        existing.setDescription("d");
        existing.setStatus("todo");
        existing.setPriority("medium");
        existing.setProjectId(1L);

        Task updated = new Task();
        updated.setId(30L);
        updated.setTaskKey("ENG-30");
        updated.setTitle("T");
        updated.setDescription("d");
        updated.setStatus("todo");
        updated.setPriority("medium");
        updated.setProjectId(1L);

        TaskLabelItemRequest item = new TaskLabelItemRequest();
        item.setName("newlabel");

        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setLabels(List.of(item));

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(30L)).thenReturn(updated);
        when(taskFavoriteMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(labelService.sortedNamesJoined(30L)).thenReturn("", "newlabel");

        taskService.update("ENG-30", request, 7L);

        verify(labelService).replaceTaskLabels(30L, 1L, request.getLabels());
        verify(taskActivityService).recordFieldChange(30L, 7L, "labels", null, "newlabel");
        verify(labelService).fillLabelsForTasks(anyList());
    }

    @Test
    void updatePropagatesLabelServiceError() {
        Task existing = new Task();
        existing.setId(31L);
        existing.setTaskKey("ENG-31");
        existing.setTitle("T");
        existing.setStatus("todo");
        existing.setPriority("medium");
        existing.setProjectId(1L);

        TaskLabelItemRequest item = new TaskLabelItemRequest();
        item.setId(5L);

        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setLabels(List.of(item));

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(labelService.sortedNamesJoined(31L)).thenReturn("");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("标签不属于当前任务所在项目"))
                .when(labelService)
                .replaceTaskLabels(anyLong(), anyLong(), any());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> taskService.update("ENG-31", request, 7L));
    }
}
