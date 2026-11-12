package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.TaskCollaborationInputs;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskDependency;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.CollaborationBatchMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final TaskExecutionOperationMapper operations = mock(TaskExecutionOperationMapper.class);
    private final ProjectDocumentMapper documents = mock(ProjectDocumentMapper.class);
    private final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    private final TaskPermissionGuard permissions = mock(TaskPermissionGuard.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final CollaborationBatchMapper batches = mock(CollaborationBatchMapper.class);
    private final TaskStatusService statuses = mock(TaskStatusService.class);
    private final ObjectMapper json = new ObjectMapper();
    private TaskCollaborationInputService service;

    @BeforeEach
    void setUp() {
        service = new TaskCollaborationInputService(projects, tasks, dependencies, attempts, documents,
                bindings, permissions, access, statuses, json, operations, batches);
        when(tasks.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(projects.lockProject(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void inputReadUsesTaskAndDependenciesAfterProjectLock() throws Exception {
        Task observed = task(1, 6, "todo", 3);
        Task locked = task(1, 6, "todo", 4);
        when(permissions.requireTaskAccessByKey("LL-1", 8L)).thenReturn(observed);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(locked);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 5), edge(1, 2)));

        TaskCollaborationInputs value = service.getInputs("LL-1", 8L);

        assertEquals(4L, value.inputRevision());
        assertEquals(List.of(2L, 5L), value.prerequisiteTaskIds());
        var ordered = org.mockito.Mockito.inOrder(projects, tasks, dependencies);
        ordered.verify(projects).lockProject(6L);
        ordered.verify(tasks).selectByIdForUpdate(1L);
        ordered.verify(dependencies).selectByTaskId(1L);
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
    void configurationAllowsReadOnlyTaskAndUnfinishedValidPrerequisite() {
        Task current = task(1, 6, "todo", 4);
        Task prerequisite = task(2, 6, "todo", 1);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        when(tasks.selectById(2L)).thenReturn(prerequisite);
        assertTrue(service.evaluateConfiguration(current, 4L).ready());
        var waiting = service.evaluateInputs(current, 4L, 10L);
        assertFalse(waiting.ready());
        assertEquals("前置任务缺少可核验成果", waiting.reason());
        verify(attempts, never()).selectByTaskId(anyLong());
    }

    @Test
    void verifiedTraceCandidatesSurviveNewUnrelatedWritesAndSourceCompletion() {
        Task current = task(1, 6, "todo", 4);
        Task source = task(2, 6, "in_progress", 3);
        when(tasks.selectById(2L)).thenReturn(source);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        ProjectDeviceBinding binding = new ProjectDeviceBinding();
        binding.setId(10L); binding.setProjectId(6L); binding.setRevision(5L);
        when(bindings.selectById(10L)).thenReturn(binding);
        CollaborationBatch owner = new CollaborationBatch();
        owner.setProjectId(6L); owner.setBindingId(10L); owner.setBindingRevision(5L);
        when(batches.selectById("source-batch")).thenReturn(owner);
        TaskExecutionAttempt upstream = new TaskExecutionAttempt();
        upstream.setId(20L); upstream.setTaskId(2L); upstream.setBindingId(10L); upstream.setBatchId("source-batch");
        upstream.setInputRevision(3L); upstream.setStatus("running");
        var points = json.createArrayNode();
        var first = json.createObjectNode().put("operationId", "write-a");
        first.putArray("artifactRefs").add("docs/a.md");
        first.putArray("artifactObservations").add(json.createObjectNode().put("path", "docs/a.md")
                .put("state", "PRESENT").put("contentVersion", "sha256:" + "a".repeat(64)));
        points.add(first);
        var second = json.createObjectNode().put("operationId", "write-b");
        second.putArray("artifactRefs").add("docs/b.md");
        second.putArray("artifactObservations").add(json.createObjectNode().put("path", "docs/b.md")
                .put("state", "PRESENT").put("contentVersion", "sha256:" + "b".repeat(64)));
        points.add(second);
        upstream.setCheckpoints(points);
        when(attempts.selectByTaskId(2L)).thenReturn(List.of(upstream));
        when(attempts.selectById(20L)).thenReturn(upstream);
        var candidate = service.evaluateInputs(current, 4L, 10L);
        assertFalse(candidate.ready());
        assertEquals(TaskCollaborationInputService.TRACE_PENDING, candidate.reason());
        assertEquals(2, candidate.prerequisites().get(0).evidence().path("artifactObservations").size());
        assertEquals("write-a", candidate.prerequisites().get(0).evidence().path("traceOperationIds").path("docs/a.md").asText());
        TaskExecutionAttempt downstream = new TaskExecutionAttempt();
        downstream.setBindingId(10L); downstream.setInputRevision(4L);
        downstream.setPrerequisiteVersions(json.valueToTree(candidate.prerequisites()));
        assertTrue(service.matchesPrerequisiteSnapshot(current, downstream));
        var third = json.createObjectNode().put("operationId", "write-a-new");
        third.putArray("artifactRefs").add("docs/a.md");
        third.putArray("artifactObservations").add(json.createObjectNode().put("path", "docs/a.md")
                .put("state", "PRESENT").put("contentVersion", "sha256:" + "c".repeat(64)));
        points.add(third);
        var newer = service.evaluateInputs(current, 4L, 10L);
        assertEquals("write-a-new", newer.prerequisites().get(0).evidence().path("traceOperationIds").path("docs/a.md").asText());
        assertTrue(service.matchesPrerequisiteSnapshot(current, downstream));
        upstream.setStatus("succeeded"); upstream.setStopConfirmedAt(LocalDateTime.now());
        var delivery = json.createObjectNode(); delivery.putArray("artifactRefs").add("docs/b.md");
        upstream.setResultEvidence(delivery);
        source.setStatus("done");
        assertTrue(service.matchesPrerequisiteSnapshot(current, downstream));
        assertEquals(TaskCollaborationInputService.TRACE_PENDING, service.evaluateInputs(current, 4L, 10L).reason());
        source.setStatus("canceled");
        assertFalse(service.matchesPrerequisiteSnapshot(current, downstream));
        source.setStatus("done");
        source.setInputRevision(4L);
        assertFalse(service.matchesPrerequisiteSnapshot(current, downstream));
        source.setInputRevision(3L);
        binding.setRevision(6L);
        assertFalse(service.matchesPrerequisiteSnapshot(current, downstream));
    }

    @Test
    void historicalCheckpointReferencesAloneCannotUnlock() {
        Task current = task(1, 6, "todo", 4);
        Task source = task(2, 6, "in_progress", 3);
        when(tasks.selectById(2L)).thenReturn(source);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        ProjectDeviceBinding binding = new ProjectDeviceBinding();
        binding.setId(10L); binding.setProjectId(6L); binding.setRevision(5L);
        when(bindings.selectById(10L)).thenReturn(binding);
        CollaborationBatch owner = new CollaborationBatch();
        owner.setProjectId(6L); owner.setBindingId(10L); owner.setBindingRevision(5L);
        when(batches.selectById("source-batch")).thenReturn(owner);
        TaskExecutionAttempt upstream = new TaskExecutionAttempt();
        upstream.setId(20L); upstream.setTaskId(2L); upstream.setBindingId(10L); upstream.setBatchId("source-batch");
        upstream.setInputRevision(3L); upstream.setStatus("running");
        var old = json.createObjectNode().put("operationId", "legacy");
        old.putArray("artifactRefs").add("docs/a.md");
        upstream.setCheckpoints(json.createArrayNode().add(old));
        when(attempts.selectByTaskId(2L)).thenReturn(List.of(upstream));
        assertFalse(service.evaluateInputs(current, 4L, 10L).ready());
        assertTrue(service.evaluateInputs(current, 4L, 10L).prerequisites().isEmpty());
    }

    @Test
    void waitingPathSelectsLatestRelatedTraceAcrossAttempts() {
        Task current = task(1, 6, "todo", 4);
        Task source = task(2, 6, "in_progress", 3);
        when(tasks.selectById(2L)).thenReturn(source);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        var binding = new ProjectDeviceBinding(); binding.setId(10L); binding.setProjectId(6L); binding.setRevision(5L);
        when(bindings.selectById(10L)).thenReturn(binding);
        var owner = new CollaborationBatch(); owner.setProjectId(6L); owner.setBindingId(10L); owner.setBindingRevision(5L);
        when(batches.selectById("batch")).thenReturn(owner);
        var attemptsBySource = new java.util.ArrayList<TaskExecutionAttempt>();
        for (int i = 1; i <= 3; i++) {
            var attempt = new TaskExecutionAttempt(); attempt.setId((long) i); attempt.setTaskId(2L);
            attempt.setBindingId(10L); attempt.setBatchId("batch"); attempt.setInputRevision(3L); attempt.setStatus("succeeded");
            attempt.setStopConfirmedAt(LocalDateTime.now());
            String path = i == 3 ? "docs/bar.md" : "docs/foo.md";
            var fact = json.createObjectNode().put("operationId", "write-" + i);
            fact.putArray("artifactRefs").add(path);
            fact.putArray("artifactObservations").add(json.createObjectNode().put("path", path)
                    .put("state", "PRESENT").put("contentVersion", "sha256:" + String.valueOf(i).repeat(64)));
            attempt.setCheckpoints(json.createArrayNode().add(fact)); attemptsBySource.add(attempt);
        }
        when(attempts.selectByTaskId(2L)).thenReturn(attemptsBySource);
        var consumer = new TaskExecutionAttempt(); consumer.setInputRevision(4L); consumer.setBatchId("batch");
        var wait = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "WAIT_FOR_CHANGE");
        wait.putObject("review").put("sourceTaskId", 2L).putArray("observations")
                .add(json.createObjectNode().put("path", "docs/foo.md"));
        consumer.setCheckpoints(json.createArrayNode().add(wait));
        when(attempts.selectByTaskId(1L)).thenReturn(List.of(consumer));
        var result = service.evaluateInputs(current, 4L, 10L, "batch");
        assertEquals(2L, result.prerequisites().get(0).attemptId());
        assertEquals("write-2", result.prerequisites().get(0).evidence().path("traceOperationIds").path("docs/foo.md").asText());
        var candidates = result.prerequisites().get(0).traceCandidates();
        assertEquals(List.of(2L, 3L), candidates.stream().map(TaskCollaborationInputService.TraceCandidate::attemptId).toList());
        assertEquals("write-3", candidates.get(1).evidence().path("traceOperationIds").path("docs/bar.md").asText());
        assertEquals(3L, service.evaluateInputs(current, 4L, 10L, "another-batch").prerequisites().get(0).attemptId());
    }

    @Test
    void configurationRejectsCrossProjectPrerequisiteEvenWhenUnfinished() {
        Task current = task(1, 6, "todo", 4);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        when(tasks.selectById(2L)).thenReturn(task(2, 7, "todo", 1));
        var invalid = service.evaluateConfiguration(current, 4L);
        assertFalse(invalid.ready());
        assertEquals("前置任务必须属于同一项目", invalid.reason());
    }

    @Test
    void projectDeletionRequestsStopAndKeepsAcceptedBatchesWhenNoTaskAttemptExists() {
        when(batches.countAcceptedByProject(6L)).thenReturn(1L);
        assertTrue(service.requestProjectStop(6L, 8L));
        verify(batches).requestProjectStop(eq(6L), any(LocalDateTime.class));
        assertThrows(ConflictOperationException.class, () -> service.clearProjectFacts(6L, List.of()));
        verify(batches, never()).deleteTasksByProject(anyLong());
        verify(batches, never()).deleteByProject(anyLong());
        verify(operations, never()).deleteByProjectId(anyLong());
    }

    @Test
    void projectDeletionRemovesBatchTasksAndBatchesBeforeBindings() {
        service.clearProjectFacts(6L, List.of());
        var order = org.mockito.Mockito.inOrder(batches, bindings);
        order.verify(batches).countAcceptedByProject(6L);
        order.verify(batches).deleteTasksByProject(6L);
        order.verify(batches).deleteByProject(6L);
        order.verify(bindings).delete(any(LambdaQueryWrapper.class));
        verify(operations).deleteByProjectId(6L);
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
    void cancelRequestsStopUntilAttemptIsConfirmed() {
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(true);
        service.onTaskInputChanged(new CollaborationTaskInputChangedEvent(6L, 1L, 8L, false, true));
        verify(attempts).requestStopForTask(eq(1L), any(LocalDateTime.class), eq("任务取消"));
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
    }

    @Test
    void parentAssignmentRejectsCollaborationParticipationOrConfirmedHistory() throws Exception {
        Task parent = task(1, 6, "done", 4);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(parent);
        when(attempts.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(dependencies.hasCollaborationParticipation(1L)).thenReturn(true);
        assertThrows(ConflictOperationException.class,
                () -> service.onParentAssigned(new CollaborationParentAssignedEvent(6L, 1L)));

        assertThrows(ConflictOperationException.class,
                () -> service.onParentAssigned(new CollaborationParentAssignedEvent(6L, 1L)));
        verify(dependencies, times(2)).hasCollaborationParticipation(1L);
    }

    @Test
    void discoveredRelationshipInvalidatesCurrentAndDownstreamAttempts() {
        Task current = task(1, 6, "in_progress", 4);
        Task downstream = task(3, 6, "in_progress", 7);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(2L)).thenReturn(task(2, 6, "todo", 1));
        when(tasks.selectByIdForUpdate(3L)).thenReturn(downstream);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of());
        when(dependencies.selectByTaskId(2L)).thenReturn(List.of());
        when(dependencies.selectByPrerequisiteTaskId(1L)).thenReturn(List.of(edge(3, 1)));
        when(dependencies.insert(any(TaskDependency.class))).thenReturn(1);

        assertTrue(service.changeDependency(current, 2L, true, 8L));
        verify(dependencies).insert(any(TaskDependency.class));
        verify(attempts).requestStopForTask(eq(1L), any(LocalDateTime.class), eq("协作关系变化"));
        verify(attempts).requestStopForTask(eq(3L), any(LocalDateTime.class), eq("协作关系变化"));
        verify(tasks, times(2)).update(eq(null), any(UpdateWrapper.class));
    }

    @Test
    void reworkPreservesHistoricalAttemptAndInvalidatesSourceAndDownstream() {
        Task source = task(2, 6, "done", 4);
        Task downstream = task(3, 6, "in_progress", 7);
        when(tasks.selectByIdForUpdate(2L)).thenReturn(source);
        when(tasks.selectByIdForUpdate(3L)).thenReturn(downstream);
        when(dependencies.selectByPrerequisiteTaskId(2L)).thenReturn(List.of(edge(3, 2)));
        service.reopenForRework(6L, 2L, 8L);
        verify(attempts).requestStopForTask(eq(2L), any(LocalDateTime.class), eq("前置成果需要返工"));
        verify(attempts).requestStopForTask(eq(3L), any(LocalDateTime.class), eq("前置成果需要返工"));
        verify(tasks, times(2)).update(eq(null), any(UpdateWrapper.class));
        verify(statuses).updateState(eq(2L), eq("todo"), eq(0), eq(8L), any(LocalDateTime.class));
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
    }

    @Test
    void duplicateRelationshipLeavesVersionAndAttemptsUntouched() {
        Task current = task(1, 6, "in_progress", 4);
        when(tasks.selectByIdForUpdate(2L)).thenReturn(task(2, 6, "done", 1));
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        assertFalse(service.changeDependency(current, 2L, true, 8L));
        verify(dependencies, never()).insert(any(TaskDependency.class));
        verify(tasks, never()).update(eq(null), any(UpdateWrapper.class));
        verify(attempts, never()).requestStopForTask(anyLong(), any(), any());
    }

    @Test
    void relationshipRejectsCyclesCrossProjectAndNonLeaf() {
        Task current = task(1, 6, "in_progress", 4);
        when(tasks.selectByIdForUpdate(2L)).thenReturn(task(2, 6, "todo", 1));
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of());
        when(dependencies.selectByTaskId(2L)).thenReturn(List.of(edge(2, 1)));
        assertThrows(ConflictOperationException.class, () -> service.changeDependency(current, 2L, true, 8L));
        when(tasks.selectByIdForUpdate(2L)).thenReturn(task(2, 7, "todo", 1));
        assertThrows(RuntimeException.class, () -> service.changeDependency(current, 2L, true, 8L));
        when(tasks.selectByIdForUpdate(2L)).thenReturn(task(2, 6, "todo", 1));
        when(tasks.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThrows(ConflictOperationException.class, () -> service.changeDependency(current, 2L, true, 8L));
        verify(dependencies, never()).insert(any(TaskDependency.class));
    }

    @Test
    void removalInvalidatesInputsAndStopsAffectedAttempts() {
        Task current = task(1, 6, "in_progress", 4);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(current);
        when(tasks.selectByIdForUpdate(2L)).thenReturn(task(2, 6, "done", 1));
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        when(dependencies.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        assertTrue(service.changeDependency(current, 2L, false, 8L));
        verify(dependencies).delete(any(LambdaQueryWrapper.class));
        verify(attempts).requestStopForTask(eq(1L), any(LocalDateTime.class), eq("协作关系变化"));
    }

    @Test
    void dependencyBecomesReadyOnlyAfterCurrentDeliveryAndStopConfirmation() {
        Task current = task(1, 6, "todo", 5);
        Task prerequisite = task(2, 6, "todo", 3);
        when(dependencies.selectByTaskId(1L)).thenReturn(List.of(edge(1, 2)));
        when(tasks.selectById(2L)).thenReturn(prerequisite);
        assertFalse(service.evaluateInputs(current, 5L, 10L).ready());
        prerequisite.setStatus("done");
        TaskExecutionAttempt delivery = new TaskExecutionAttempt();
        delivery.setId(12L); delivery.setTaskId(2L); delivery.setBatchId("batch"); delivery.setStatus("succeeded"); delivery.setBindingId(10L); delivery.setInputRevision(3L);
        var result = json.createObjectNode().put("result", "可用"); result.putArray("artifactRefs");
        delivery.setResultEvidence(result);
        var binding = new ProjectDeviceBinding(); binding.setId(10L); binding.setProjectId(6L); binding.setRevision(1L);
        when(bindings.selectById(10L)).thenReturn(binding);
        var batch = new CollaborationBatch(); batch.setProjectId(6L); batch.setBindingId(10L); batch.setBindingRevision(1L);
        when(batches.selectById("batch")).thenReturn(batch);
        when(attempts.selectByTaskId(2L)).thenReturn(List.of(delivery));
        assertFalse(service.evaluateInputs(current, 5L, 10L).ready());
        delivery.setStopConfirmedAt(LocalDateTime.now());
        assertTrue(service.evaluateInputs(current, 5L, 10L).ready());
        assertEquals(12L, service.evaluateInputs(current, 5L, 10L).prerequisites().get(0).attemptId());
        result.withArray("artifactRefs").add("docs/unverified.md");
        assertFalse(service.evaluateInputs(current, 5L, 10L).ready());
        result.withArray("artifactRefs").removeAll();
        batch.setBindingRevision(2L);
        assertFalse(service.evaluateInputs(current, 5L, 10L).ready());
        assertFalse(service.evaluateInputs(current, 4L, 10L).ready());
    }

    @Test
    void opposingEdgesInCoordinatedOrderRejectSecondEdge() {
        Task first = task(1, 6, "in_progress", 1);
        Task second = task(2, 6, "in_progress", 1);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(first);
        when(tasks.selectByIdForUpdate(2L)).thenReturn(second);
        var saved = new java.util.ArrayList<TaskDependency>();
        when(dependencies.selectByTaskId(anyLong())).thenAnswer(call -> saved.stream()
                .filter(item -> item.getTaskId().equals(call.getArgument(0))).toList());
        when(dependencies.insert(any(TaskDependency.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0)); return 1;
        });
        assertTrue(service.changeDependency(first, 2L, true, 8L));
        assertThrows(ConflictOperationException.class, () -> service.changeDependency(second, 1L, true, 8L));
        assertEquals(1, saved.size());
        verify(dependencies, times(1)).insert(any(TaskDependency.class));
    }

    private Task task(long id, long projectId, String status, long revision) {
        Task task = new Task();
        task.setId(id);
        task.setProjectId(projectId);
        task.setStatus(status);
        task.setInputRevision(revision);
        return task;
    }

    private TaskDependency edge(long taskId, long prerequisiteId) {
        TaskDependency edge = new TaskDependency();
        edge.setTaskId(taskId);
        edge.setPrerequisiteTaskId(prerequisiteId);
        return edge;
    }
}
