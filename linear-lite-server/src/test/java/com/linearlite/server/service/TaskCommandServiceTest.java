package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class TaskCommandServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private TaskFavoriteMapper taskFavoriteMapper;
    @Mock
    private TaskActivityService taskActivityService;
    @Mock
    private TaskPermissionGuard taskPermissionGuard;
    @Mock
    private TaskSequenceService taskSequenceService;
    @Mock
    private LabelService labelService;
    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private CodexDispatchService codexDispatchService;
    @Mock
    private TaskStatusService taskStatusService;
    @Mock
    private TaskHierarchyCompletionService taskHierarchyCompletionService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TaskCommandService taskCommandService;

    @BeforeEach
    void setUp() {
        taskCommandService = new TaskCommandService(
                taskMapper,
                projectMapper,
                taskFavoriteMapper,
                taskActivityService,
                taskPermissionGuard,
                taskSequenceService,
                labelService,
                taskQueryService,
                userMapper,
                codexDispatchService,
                taskStatusService,
                taskHierarchyCompletionService
        );
        taskCommandService.setEventPublisher(eventPublisher);
        org.mockito.Mockito.lenient().when(taskHierarchyCompletionService.completeEligibleAncestors(
                anyLong(), anyLong(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(taskHierarchyCompletionService.completeEligibleParentChain(
                anyLong(), anyLong(), any())).thenReturn(List.of());
    }

    @Test
    void createPersistsTaskAndRecordsActivity() {
        Project project = new Project();
        project.setId(1L);
        project.setIdentifier("ENG");
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(taskSequenceService.reserveTaskNumbers(1L, "ENG", 1)).thenReturn(9L);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(99L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));
        Task inserted = new Task();
        inserted.setId(99L);
        inserted.setTaskKey("ENG-9");
        inserted.setProjectId(1L);
        when(taskMapper.selectById(99L)).thenReturn(inserted);
        when(userMapper.selectById(2L)).thenReturn(user(2L, User.TYPE_HUMAN));

        Task result = taskCommandService.create(
                1L, 7L, null, "Title", "Desc", "todo", "high", 2L,
                null, null, 50, Collections.emptyList()).task();

        assertEquals("ENG-9", result.getTaskKey());
        verify(taskPermissionGuard).requireProjectMember(1L, 7L);
        verify(taskActivityService).recordAction(99L, 7L, "created");
        verify(taskQueryService).enrichForUser(Collections.singletonList(inserted), 7L);
        verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.TASK, 99L));
    }

    @Test
    void createAssignedToCodexDispatchesAfterTaskPersistence() {
        Project project = new Project();
        project.setId(1L);
        project.setIdentifier("ENG");
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(taskSequenceService.reserveTaskNumbers(1L, "ENG", 1)).thenReturn(10L);
        doAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));
        Task inserted = task(100L, "ENG-10", 99L);
        when(taskMapper.selectById(100L)).thenReturn(inserted);
        when(userMapper.selectById(99L)).thenReturn(user(99L, User.TYPE_CODEX));

        taskCommandService.create(
                1L, 7L, null, "Codex task", null, "todo", "medium", 99L,
                null, null, 0, Collections.emptyList());

        verify(codexDispatchService).dispatchAssignedTask(inserted, 7L);
    }

    @Test
    void addFavoriteCreatesRelationOnlyOnce() {
        Task task = new Task();
        task.setId(12L);
        task.setTaskKey("ENG-12");
        task.setProjectId(1L);

        when(taskPermissionGuard.requireTaskAccessByKey("ENG-12", 9L)).thenReturn(task);
        when(taskFavoriteMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(taskMapper.selectById(12L)).thenReturn(task);

        Task result = taskCommandService.addFavorite("ENG-12", 9L);

        ArgumentCaptor<TaskFavorite> captor = ArgumentCaptor.forClass(TaskFavorite.class);
        verify(taskFavoriteMapper).insert(captor.capture());
        verify(taskActivityService, never()).recordAction(12L, 9L, "favorited");
        assertEquals(12L, captor.getValue().getTaskId());
        assertEquals(9L, captor.getValue().getUserId());
        verify(taskQueryService).enrichForUser(Collections.singletonList(result), 9L);
    }

    @Test
    void removeFavoriteDeletesExistingRelation() {
        Task task = new Task();
        task.setId(13L);
        task.setTaskKey("ENG-13");
        task.setProjectId(1L);

        when(taskPermissionGuard.requireTaskAccessByKey("ENG-13", 9L)).thenReturn(task);
        when(taskMapper.selectById(13L)).thenReturn(task);

        Task result = taskCommandService.removeFavorite("ENG-13", 9L);

        verify(taskFavoriteMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskActivityService, never()).recordAction(13L, 9L, "unfavorited");
        verify(taskFavoriteMapper, never()).insert(any());
        verify(taskQueryService).enrichForUser(Collections.singletonList(result), 9L);
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
        when(userMapper.selectById(2L)).thenReturn(user(2L, User.TYPE_HUMAN));

        taskCommandService.update("ENG-21", request, 7L);

        verify(taskPermissionGuard).requireProjectMember(1L, 7L);
        verify(taskActivityService).recordFieldChange(21L, 7L, "title", "Old title", "New title");
        verify(taskStatusService).updateState(
                org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq("in_progress"),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(7L), any());
        verify(taskActivityService).recordFieldChange(21L, 7L, "priority", "medium", "high");
        verify(taskActivityService).recordAssigneeChange(21L, 7L, 1L, 2L);
        verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.TASK, 21L));
    }

    @Test
    void updateDoesNotRecordDescriptionChanges() {
        Task existing = task(31L, "ENG-31", null);
        existing.setDescription("Old description");
        Task updated = task(31L, "ENG-31", null);
        updated.setDescription("New description");
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setDescription("New description");

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(31L)).thenReturn(updated);

        taskCommandService.update("ENG-31", request, 7L);

        verify(taskActivityService, never()).recordFieldChange(31L, 7L, "description", "Old description", "New description");
    }

    @Test
    void updateFirstAssignmentToCodexDispatchesExactlyOnce() {
        Task existing = task(40L, "ENG-40", null);
        Task updated = task(40L, "ENG-40", 99L);
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setAssigneeId(99L);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(40L)).thenReturn(updated);
        when(userMapper.selectById(99L)).thenReturn(user(99L, User.TYPE_CODEX));

        taskCommandService.update("ENG-40", request, 7L);

        verify(codexDispatchService).dispatchAssignedTask(updated, 7L);
    }

    @Test
    void updateWhileCodexAlreadyAssignedDoesNotDispatchAgain() {
        Task existing = task(41L, "ENG-41", 99L);
        Task updated = task(41L, "ENG-41", 99L);
        updated.setTitle("Changed");
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle("Changed");
        request.setAssigneeId(99L);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(41L)).thenReturn(updated);

        taskCommandService.update("ENG-41", request, 7L);

        verify(codexDispatchService, never()).dispatchAssignedTask(any(), anyLong());
    }

    @Test
    void dispatchFailurePropagatesFromTransactionalAssigneeChange() throws Exception {
        Task existing = task(42L, "ENG-42", null);
        Task updated = task(42L, "ENG-42", 99L);
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setAssigneeId(99L);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(taskMapper.selectById(42L)).thenReturn(updated);
        when(userMapper.selectById(99L)).thenReturn(user(99L, User.TYPE_CODEX));
        org.mockito.Mockito.doThrow(new IllegalStateException("runner offline"))
                .when(codexDispatchService).dispatchAssignedTask(updated, 7L);

        assertThrows(IllegalStateException.class, () -> taskCommandService.update("ENG-42", request, 7L));
        Assertions.assertNotNull(TaskCommandService.class.getMethod(
                "update", String.class, UpdateTaskRequest.class, Long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
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

        taskCommandService.update("ENG-22", request, 7L);

        verify(taskActivityService, never()).recordFieldChange(any(), any(), any(), any(), any());
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
        when(labelService.sortedNamesJoined(30L)).thenReturn("", "newlabel");

        taskCommandService.update("ENG-30", request, 7L);

        verify(labelService).replaceTaskLabels(30L, 1L, request.getLabels());
        verify(taskActivityService).recordFieldChange(30L, 7L, "labels", null, "newlabel");
        verify(taskQueryService).enrichForUser(anyList(), anyLong());
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
        when(labelService.sortedNamesJoined(31L)).thenReturn("");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("标签不属于当前任务所在项目"))
                .when(labelService)
                .replaceTaskLabels(anyLong(), anyLong(), any());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> taskCommandService.update("ENG-31", request, 7L));
    }

    private static User user(Long id, String userType) {
        User user = new User();
        user.setId(id);
        user.setUserType(userType);
        return user;
    }

    private static Task task(Long id, String taskKey, Long assigneeId) {
        Task task = new Task();
        task.setId(id);
        task.setTaskKey(taskKey);
        task.setTitle("Title");
        task.setStatus("todo");
        task.setPriority("medium");
        task.setProjectId(1L);
        task.setAssigneeId(assigneeId);
        return task;
    }
}
