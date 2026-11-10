package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.PublicContextRef;
import com.linearlite.server.dto.TaskCollaborationInputs;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskDependency;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskDependencyMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskCollaborationInputServiceTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskDependencyMapper dependencies = mock(TaskDependencyMapper.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final ProjectDocumentMapper documents = mock(ProjectDocumentMapper.class);
    private final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    private final TaskPermissionGuard permissions = mock(TaskPermissionGuard.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final TaskStatusService statuses = mock(TaskStatusService.class);
    private final ObjectMapper json = new ObjectMapper();
    private TaskCollaborationInputService service;

    @BeforeEach
    void setUp() {
        service = new TaskCollaborationInputService(projects, tasks, dependencies, attempts, documents,
                bindings, permissions, access, statuses, json, mock(TaskExecutionOperationMapper.class));
        when(tasks.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(projects.lockProject(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void inputReadUsesTaskAndDependenciesAfterProjectLock() throws Exception {
        Task observed = task(1, 6, "todo", 3);
        Task locked = task(1, 6, "todo", 4);
        locked.setWriteScopes(List.of("src/"));
        locked.setPublicContextRefs(json.readTree("[{\"documentId\":101,\"version\":2}]"));
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(observed);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(locked);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 5), edge(1, 2)));

        TaskCollaborationInputs value = service.getInputs("LL-1", 8L);

        assertEquals(4L, value.inputRevision());
        assertEquals(List.of("src/"), value.writeScopes());
        assertEquals(List.of(new PublicContextRef(101L, 2L)), value.publicContextRefs());
        assertEquals(List.of(2L, 5L), value.prerequisiteTaskIds());
        var ordered = org.mockito.Mockito.inOrder(projects, tasks, dependencies);
        ordered.verify(projects).lockProject(6L);
        ordered.verify(tasks).selectByIdForUpdate(1L);
        ordered.verify(dependencies).selectByTaskId(1L);
    }

    @Test
    void rejectsSelfDuplicateCrossProjectAndCycles() {
        Task current = task(1, 6, "todo", 4);
        Task sameProject = task(2, 6, "todo", 2);
        Task otherProject = task(3, 7, "todo", 1);
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(tasks.selectById(2L)).thenReturn(sameProject);
        when(tasks.selectById(3L)).thenReturn(otherProject);

        assertThrows(IllegalArgumentException.class, () -> replace(List.of(1L)));
        assertThrows(IllegalArgumentException.class, () -> replace(List.of(2L, 2L)));
        assertThrows(IllegalArgumentException.class, () -> replace(List.of(3L)));
        when(dependencies.selectByTaskId(2L)).thenReturn(List.of(edge(2, 1)));
        assertThrows(IllegalArgumentException.class, () -> replace(List.of(2L)));
        verify(dependencies, never()).insert(any(TaskDependency.class));
    }

    @Test
    void replacesDependenciesAndInvalidatesCurrentAndDownstreamDoneCards() {
        Task current = task(1, 6, "done", 4);
        Task prerequisite = task(2, 6, "done", 3);
        Task downstream = task(3, 6, "done", 7);
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(3L)).thenReturn(downstream);
        when(tasks.selectById(1L)).thenReturn(current);
        when(tasks.selectById(2L)).thenReturn(prerequisite);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of());
        when(dependencies.selectByTaskId(2L)).thenReturn(List.of());
        when(dependencies.selectByPrerequisiteTaskId(1L)).thenReturn(List.of(edge(3, 1)));
        when(dependencies.selectByPrerequisiteTaskId(3L)).thenReturn(List.of());

        replace(List.of(2L));

        verify(projects).lockProject(6L);
        ArgumentCaptor<TaskDependency> edge = ArgumentCaptor.forClass(TaskDependency.class);
        verify(dependencies).insert(edge.capture());
        assertEquals(1L, edge.getValue().getTaskId());
        assertEquals(2L, edge.getValue().getPrerequisiteTaskId());
        verify(statuses).updateState(eq(1L), eq("todo"), eq(0), eq(8L), any(LocalDateTime.class));
        verify(statuses).updateState(eq(3L), eq("todo"), eq(0), eq(8L), any(LocalDateTime.class));
        verify(attempts).requestStopForTask(eq(1L), any(LocalDateTime.class), eq("协作输入变更"));
        verify(attempts).requestStopForTask(eq(3L), any(LocalDateTime.class), eq("协作输入变更"));
        verify(tasks, times(2)).update(eq(null), any(UpdateWrapper.class));
    }

    @Test
    void documentBatchMergesVersionUpdatesAndInvalidatesSharedDescendantOnce() throws Exception {
        Task current = task(1, 6, "done", 4);
        Task downstream = task(3, 6, "done", 7);
        current.setPublicContextRefs(json.readTree("[{\"documentId\":101,\"version\":1},{\"documentId\":102,\"version\":2}]"));
        when(tasks.selectReferencingDocumentForUpdate(6L, 101L)).thenReturn(List.of(current));
        when(tasks.selectReferencingDocumentForUpdate(6L, 102L)).thenReturn(List.of(current));
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(3L)).thenReturn(downstream);
        when(dependencies.selectByPrerequisiteTaskId(1L)).thenReturn(List.of(edge(3, 1)));
        when(dependencies.selectByPrerequisiteTaskId(3L)).thenReturn(List.of());

        service.onDocumentChanged(new CollaborationDocumentChangedEvent(6L, List.of(
                new CollaborationDocumentChangedEvent.Change(101L, 3L, true),
                new CollaborationDocumentChangedEvent.Change(102L, 4L, true)), 8L));

        ArgumentCaptor<Task> patch = ArgumentCaptor.forClass(Task.class);
        verify(tasks).updateById(patch.capture());
        assertEquals(3L, patch.getValue().getPublicContextRefs().get(0).get("version").longValue());
        assertEquals(4L, patch.getValue().getPublicContextRefs().get(1).get("version").longValue());
        verify(tasks, times(2)).update(eq(null), any(UpdateWrapper.class));
        verify(attempts, times(1)).requestStopForTask(eq(3L), any(LocalDateTime.class), eq("公共约定变更"));
        verify(statuses).updateState(eq(1L), eq("todo"), eq(0), eq(8L), any(LocalDateTime.class));
        verify(statuses).updateState(eq(3L), eq("todo"), eq(0), eq(8L), any(LocalDateTime.class));
    }

    @Test
    void rejectsStaleInputAndNonSuccessfulOrWrongBindingPrerequisites() {
        Task current = task(1, 6, "in_progress", 4);
        Task prerequisite = task(2, 6, "canceled", 3);
        when(tasks.selectById(1L)).thenReturn(current);
        when(tasks.selectById(2L)).thenReturn(prerequisite);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        assertThrows(ConflictOperationException.class, () -> service.requireCurrentInputs(1L, 3L, 10L, 8L));
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        assertThrows(ConflictOperationException.class, () -> service.requireCurrentInputs(1L, 4L, 10L, 8L));
        prerequisite.setStatus("duplicate");
        assertThrows(ConflictOperationException.class, () -> service.requireCurrentInputs(1L, 4L, 10L, 8L));
        prerequisite.setStatus("done");
        TaskExecutionAttempt attempt = new TaskExecutionAttempt();
        attempt.setStatus("succeeded");
        attempt.setBindingId(11L);
        attempt.setInputRevision(3L);
        attempt.setStopConfirmedAt(LocalDateTime.now());
        attempt.setResultEvidence(json.createObjectNode().put("artifact", "src/a"));
        when(attempts.selectByTaskId(2L)).thenReturn(List.of(attempt));
        assertThrows(ConflictOperationException.class, () -> service.requireCurrentInputs(1L, 4L, 10L, 8L));
    }

    @Test
    void referencedDocumentRequiresActiveSameProjectAndExactVersion() {
        Task current = task(1, 6, "todo", 4);
        ProjectDocument document = new ProjectDocument();
        document.setId(101L);
        document.setProjectId(6L);
        document.setVersion(2L);
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(documents.selectById(101L)).thenReturn(document);
        assertThrows(ConflictOperationException.class, () -> service.replaceInputs("LL-1",
                new TaskCollaborationInputs(4L, true, List.of("src/"), List.of(new PublicContextRef(101L, 1L)), List.of()), 8L));
        document.setArchivedAt(LocalDateTime.now());
        assertThrows(ConflictOperationException.class, () -> service.replaceInputs("LL-1",
                new TaskCollaborationInputs(4L, true, List.of("src/"), List.of(new PublicContextRef(101L, 2L)), List.of()), 8L));
    }

    @Test
    void rejectsNonCanonicalScopeAliasesAndPreservesDirectorySuffix() {
        Task current = task(1, 6, "todo", 4);
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        for (List<String> scopes : List.of(List.of("src/./x"), List.of("src//x"), List.of("src/../x"),
                List.of("src", "src/"))) {
            assertThrows(IllegalArgumentException.class, () -> service.replaceInputs("LL-1",
                    new TaskCollaborationInputs(4L, true, scopes, List.of(), List.of()), 8L));
        }
        when(tasks.selectById(1L)).thenReturn(current);
        service.replaceInputs("LL-1", new TaskCollaborationInputs(4L, true, List.of("src/"),
                List.of(), List.of()), 8L);
        ArgumentCaptor<Task> patch = ArgumentCaptor.forClass(Task.class);
        verify(tasks).updateById(patch.capture());
        assertEquals(List.of("src/"), patch.getValue().getWriteScopes());
    }

    @Test
    void ordinaryDoneTaskEditPreservesCompletionWithoutCollaborationFacts() {
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(false);
        service.onTaskInputChanged(new CollaborationTaskInputChangedEvent(6L, 1L, 8L, true, false));
        verify(tasks, never()).update(eq(null), any(UpdateWrapper.class));
        verify(statuses, never()).updateState(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(attempts, never()).requestStopForTask(anyLong(), any(), any());
    }

    @Test
    void disabledTaskWithHistoryStillInvalidatesAndStopsAttempt() {
        Task current = task(1, 6, "done", 4);
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(true);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(dependencies.selectByPrerequisiteTaskId(1L)).thenReturn(List.of());
        service.onTaskInputChanged(new CollaborationTaskInputChangedEvent(6L, 1L, 8L, true, false));
        verify(statuses).updateState(eq(1L), eq("todo"), eq(0), eq(8L), any(LocalDateTime.class));
        verify(attempts).requestStopForTask(eq(1L), any(LocalDateTime.class), any());
    }

    @Test
    void cancelRequestsStopWithoutReleasingFrozenScopes() {
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(true);
        service.onTaskInputChanged(new CollaborationTaskInputChangedEvent(6L, 1L, 8L, false, true));
        verify(attempts).requestStopForTask(eq(1L), any(LocalDateTime.class), eq("任务取消"));
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
    }

    @Test
    void parentAssignmentRejectsPublicReferencesOrConfirmedHistory() throws Exception {
        Task parent = task(1, 6, "done", 4);
        parent.setPublicContextRefs(json.readTree("[{\"documentId\":101,\"version\":2}]"));
        when(tasks.selectByIdForUpdate(1L)).thenReturn(parent);
        when(attempts.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(true);
        assertThrows(ConflictOperationException.class,
                () -> service.onParentAssigned(new CollaborationParentAssignedEvent(6L, 1L)));

        parent.setPublicContextRefs(json.createArrayNode());
        assertThrows(ConflictOperationException.class,
                () -> service.onParentAssigned(new CollaborationParentAssignedEvent(6L, 1L)));
        verify(dependencies, times(2)).hasCollaborationParticipation(1L);
    }

    @Test
    void disabledDraftScopeUpdatesRevisionWithoutReopeningOrdinaryDoneTask() {
        Task current = task(1, 6, "done", 4);
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(tasks.selectById(1L)).thenReturn(current);
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(false);
        service.replaceInputs("LL-1", new TaskCollaborationInputs(4L, false, List.of("src/"),
                List.of(), List.of()), 8L);
        verify(tasks).update(eq(null), any(UpdateWrapper.class));
        verify(statuses, never()).updateState(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(attempts, never()).requestStopForTask(anyLong(), any(), any());
    }

    private Task replace(List<Long> prerequisites) {
        return service.replaceInputs("LL-1", new TaskCollaborationInputs(4L, true, List.of("src/"),
                List.of(), prerequisites), 8L);
    }

    private Task task(long id, long projectId, String status, long revision) {
        Task task = new Task();
        task.setId(id);
        task.setProjectId(projectId);
        task.setStatus(status);
        task.setInputRevision(revision);
        task.setAgentExecutionEnabled(false);
        task.setWriteScopes(List.of());
        task.setPublicContextRefs(json.createArrayNode());
        return task;
    }

    private TaskDependency edge(long taskId, long prerequisiteId) {
        TaskDependency edge = new TaskDependency();
        edge.setTaskId(taskId);
        edge.setPrerequisiteTaskId(prerequisiteId);
        return edge;
    }
}
