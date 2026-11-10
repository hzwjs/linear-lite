package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCollaborationClaimServiceTest {
    final CollaborationBatchAuthorizationService batches = mock(CollaborationBatchAuthorizationService.class);
    final TaskCollaborationInputService inputs = mock(TaskCollaborationInputService.class);
    final TaskExecutionAccessService access = mock(TaskExecutionAccessService.class);
    final TaskExecutionClaimCredentialService credentials = mock(TaskExecutionClaimCredentialService.class);
    final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    final TaskMapper tasks = mock(TaskMapper.class);
    final TaskStatusService statuses = mock(TaskStatusService.class);
    final ObjectMapper json = new ObjectMapper();
    final CollaborationBatchRequest batch = new CollaborationBatchRequest("batch-secret", 10L, "device", "/project");
    final CollaborationBatchAuthorizationService.BatchContext context = new CollaborationBatchAuthorizationService.BatchContext(
            "batch", 8L, 6L, 10L, 1L, "device", List.of(1L), Instant.now().plusSeconds(600));
    TaskCollaborationClaimService service;

    @BeforeEach void setup() {
        service = new TaskCollaborationClaimService(batches, inputs, access, credentials, operations, attempts, tasks, statuses, json);
        when(batches.observeBatch(any(), eq(8L), eq(10L), any(), any())).thenReturn(context);
        when(batches.authorizeTask(any(), eq(8L), eq(10L), any(), any(), eq(1L))).thenReturn(context);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of());
        when(attempts.selectByTaskId(anyLong())).thenReturn(List.of());
        when(credentials.issue(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn("claim-secret");
        when(credentials.hash("claim-secret")).thenReturn("hash");
        when(inputs.evaluateInputs(any(), anyLong(), anyLong())).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
    }

    @Test void claimCreatesAttemptAndPersistsNoCredentialInReceipt() {
        Task task = task(1, "todo", "src/a");
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        when(attempts.insert(any(TaskExecutionAttempt.class))).thenAnswer(i -> {
            ((TaskExecutionAttempt) i.getArgument(0)).setId(21L); return 1;
        });
        var result = service.claim(8L, batch, 1L, 3L, "host", "agent", "op", List.of());
        assertTrue(result.accepted());
        assertEquals(21L, result.attemptId());
        assertEquals("claim-secret", result.claimToken());
        assertFalse(result.toString().contains("claim-secret"));
        ArgumentCaptor<TaskExecutionAttempt> saved = ArgumentCaptor.forClass(TaskExecutionAttempt.class);
        verify(attempts).insert(saved.capture());
        assertEquals("hash", saved.getValue().getClaimTokenHash());
        assertEquals(List.of("src/a"), saved.getValue().getHeldWriteScopes());
        assertEquals("running", saved.getValue().getStatus());
        assertEquals(0, saved.getValue().getLeaseUntil().getNano());
        verify(statuses).updateState(eq(1L), eq("in_progress"), anyInt(), eq(8L), any());
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> response = ArgumentCaptor.forClass(JsonNode.class);
        verify(operations).save(eq(6L), eq(8L), eq("op"), eq("claim"), eq(21L), request.capture(), response.capture());
        assertFalse(request.getValue().toString().contains("secret"));
        assertFalse(response.getValue().toString().contains("secret"));
    }

    @Test void claimRejectsPastedBatchCredentialInPersistentFields() {
        assertThrows(IllegalArgumentException.class,
                () -> service.claim(8L, batch, 1L, 3L, batch.batchToken(), "agent", "op", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.claim(8L, batch, 1L, 3L, "host", "agent", batch.batchToken(), List.of()));
        verifyNoInteractions(operations);
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void overlapAndPendingAttemptRejectWithoutCreatingAnotherAttempt() {
        Task task = task(1, "todo", "src/a");
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        TaskExecutionAttempt other = attempt(22L, 2L, 10L, "src/");
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(other));
        var conflict = service.claim(8L, batch, 1L, 3L, "host", "agent", "op", List.of());
        assertFalse(conflict.accepted());
        assertEquals("写入范围与其他执行冲突", conflict.reason());
        other.setBindingId(11L);
        other.setTaskId(1L);
        var occupied = service.claim(8L, batch, 1L, 3L, "host", "agent", "op2", List.of());
        assertFalse(occupied.accepted());
        assertEquals("任务存在活动或冻结执行", occupied.reason());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void observationDistinguishesAvailableDependencyResourceAndActive() {
        Task task = task(1, "todo", "src/a");
        when(tasks.selectById(1L)).thenReturn(task);
        var prerequisite = new TaskCollaborationInputService.PrerequisiteResult(2L, 23L, 4L, json.createObjectNode().put("path", "a"));
        when(inputs.evaluateInputs(task, 3L, 10L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of(prerequisite)));
        assertEquals("DEPENDENCY_WAIT", service.observe(8L, batch, List.of()).state());
        var available = List.of(new TaskCollaborationClaimService.AvailableResult(23L, 4L));
        assertEquals("READY", service.observe(8L, batch, available).state());
        TaskExecutionAttempt other = attempt(22L, 2L, 10L, "src/");
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(other));
        var waiting = service.observe(8L, batch, available);
        assertEquals("RESOURCE_WAIT", waiting.state());
        assertEquals(22L, waiting.resourceOwners().get(0).attemptId());
        other.setTaskId(1L);
        assertEquals("ACTIVE", service.observe(8L, batch, available).state());
    }

    @Test void claimReplayUsesSameDerivedTokenWithoutWritingAgain() {
        when(operations.findReplay(eq(6L), eq(8L), eq("op"), eq("claim"), any())).thenReturn(
                json.createObjectNode().put("accepted", true).put("attemptId", 21L).put("inputRevision", 3L)
                        .put("leaseUntil", "2026-11-10T12:00:00Z"));
        var result = service.claim(8L, batch, 1L, 3L, "host", "agent", "op", List.of());
        assertEquals("claim-secret", result.claimToken());
        assertEquals(21L, result.attemptId());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
        verify(statuses, never()).updateState(any(), any(), anyInt(), any(), any());
    }

    @Test void differentBindingDoesNotBlockScopeButStaleRevisionCannotClaim() {
        Task task = task(1, "todo", "src/a");
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(attempt(22L, 2L, 11L, "src/")));
        assertEquals("READY", service.observe(8L, batch, List.of()).state());
        var result = service.claim(8L, batch, 1L, 2L, "host", "agent", "old-version", List.of());
        assertFalse(result.accepted());
        assertEquals("任务输入版本已变化", result.reason());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void expiredAttemptFreezesRangeAndCompleteRequiresSameBindingEvidence() {
        Task task = task(1, "todo", "src/a");
        when(tasks.selectById(1L)).thenReturn(task);
        TaskExecutionAttempt expired = attempt(22L, 2L, 10L, "src/");
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(expired));
        assertEquals("RESOURCE_WAIT", service.observe(8L, batch, List.of()).state());
        verify(access).requestStop(expired, "执行租约过期");

        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of());
        task.setStatus("done");
        TaskExecutionAttempt success = attempt(23L, 1L, 11L, "src/a");
        success.setStatus("succeeded"); success.setInputRevision(3L); success.setStopConfirmedAt(LocalDateTime.now());
        success.setResultEvidence(json.createObjectNode().put("path", "src/a"));
        when(attempts.selectByTaskId(1L)).thenReturn(List.of(success));
        assertEquals("BLOCKED", service.observe(8L, batch, List.of()).state());
        success.setBindingId(10L);
        assertEquals("COMPLETE", service.observe(8L, batch, List.of()).state());
    }

    @Test void conflictingExpansionStopsAndPreservesHeldScope() {
        Task task = task(1, "in_progress", "src/a");
        TaskExecutionAttempt current = attempt(21L, 1L, 10L, "src/a");
        TaskExecutionAttempt other = attempt(22L, 2L, 10L, "docs/");
        var command = new CollaborationAttemptCommand(21L, "claim-secret", 3L, "host", "agent", "expand-op");
        var owned = new TaskExecutionAccessService.OwnedAttempt(context, task, current);
        when(access.requireOwnedAttempt(8L, batch, 1L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, current));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(current, other));
        var result = service.expand(8L, batch, 1L, command, List.of("docs/a.md"));
        assertFalse(result.accepted());
        assertEquals(List.of("src/a"), result.heldWriteScopes());
        verify(access).requestStop(current, "写入范围与其他执行冲突");
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
    }

    @Test void equivalentExpansionKeepsOriginalHeldRepresentation() {
        Task task = task(1, "in_progress", "src/");
        TaskExecutionAttempt current = attempt(21L, 1L, 10L, "src/");
        var command = new CollaborationAttemptCommand(21L, "claim-secret", 3L, "host", "agent", "expand-op");
        var owned = new TaskExecutionAccessService.OwnedAttempt(context, task, current);
        when(access.requireOwnedAttempt(8L, batch, 1L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, current));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(current));
        var result = service.expand(8L, batch, 1L, command, List.of("src"));
        assertTrue(result.accepted());
        assertEquals(List.of("src/"), result.heldWriteScopes());
        verify(access, never()).requestStop(any(), any());
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
        verify(operations).save(eq(6L), eq(8L), eq("expand-op"), eq("scope"), eq(21L), any(), any());
    }

    @Test void serviceEntryPointsUseReadCommittedRollbackTransactions() throws Exception {
        for (String name : List.of("observe", "claim", "expand")) {
            var method = java.util.Arrays.stream(TaskCollaborationClaimService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = method.getAnnotation(Transactional.class);
            assertNotNull(tx);
            assertEquals(Isolation.READ_COMMITTED, tx.isolation());
            assertTrue(List.of(tx.rollbackFor()).contains(Exception.class));
        }
    }

    private Task task(long id, String status, String scope) {
        Task task = new Task(); task.setId(id); task.setProjectId(6L); task.setStatus(status);
        task.setInputRevision(3L); task.setAgentExecutionEnabled(true); task.setWriteScopes(List.of(scope));
        return task;
    }
    private TaskExecutionAttempt attempt(long id, long taskId, long bindingId, String scope) {
        TaskExecutionAttempt attempt = new TaskExecutionAttempt(); attempt.setId(id); attempt.setTaskId(taskId);
        attempt.setBindingId(bindingId); attempt.setHeldWriteScopes(List.of(scope));
        attempt.setStatus("running"); attempt.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
        return attempt;
    }
}
