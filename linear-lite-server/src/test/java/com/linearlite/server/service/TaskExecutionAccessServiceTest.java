package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskExecutionAccessServiceTest {
    @Mock CollaborationBatchAuthorizationService batches;
    @Mock TaskExecutionClaimCredentialService credentials;
    @Mock TaskMapper tasks;
    @Mock TaskExecutionAttemptMapper attempts;
    @Mock ProjectMapper projects;
    @Mock ProjectDeviceBindingMapper bindings;
    @Mock TaskCollaborationInputService inputs;
    TaskExecutionAccessService service;
    Task task;
    TaskExecutionAttempt attempt;
    CollaborationAttemptCommand command;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        service = new TaskExecutionAccessService(batches, credentials, tasks, attempts, projects, bindings, inputs);
        task = new Task(); task.setId(10L); task.setProjectId(6L); task.setInputRevision(2L);
        task.setStatus("in_progress"); task.setAgentExecutionEnabled(true);
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L); attempt.setBindingId(30L);
        attempt.setHostInstanceId("host"); attempt.setAgentInstanceId("agent"); attempt.setClaimTokenHash("hash");
        attempt.setInputRevision(2L); attempt.setStatus("running");
        attempt.setLeaseUntil(LocalDateTime.now().plusMinutes(2)); attempt.setPrerequisiteVersions(json.createArrayNode());
        command = new CollaborationAttemptCommand(20L, "secret", 2L, "host", "agent", "op");
    }

    @Test void recoveryAuthenticatesOwnerDeviceAttemptInstanceAndCredentialWithoutMemberOrLease() {
        ProjectDeviceBinding binding = binding();
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device")).thenReturn(binding);
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task);
        when(attempts.selectByIdForUpdate(20L)).thenReturn(attempt);
        when(credentials.matches("secret", "hash")).thenReturn(true);
        attempt.setLeaseUntil(LocalDateTime.now().minusDays(1));
        assertSame(attempt, service.requireRecoveryAttempt(1L, 6L, 30L, "device", 10L, command).attempt());
        InOrder order = inOrder(projects, bindings, tasks, attempts);
        order.verify(projects).lockProject(6L);
        order.verify(bindings).selectForUpdate(1L, 6L, "device");
        order.verify(tasks).selectByIdForUpdate(10L);
        order.verify(attempts).selectByIdForUpdate(20L);
        verifyNoInteractions(batches);
        var wrongAgent = new CollaborationAttemptCommand(20L, "secret", 2L, "host", "other", "op");
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireRecoveryAttempt(1L, 6L, 30L, "device", 10L, wrongAgent));
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireRecoveryAttempt(1L, 6L, 31L, "device", 10L, command));
    }

    @Test void validAttemptAcceptsExactSnapshotAndRejectsWrongRequestRevisionWithoutStop() {
        when(inputs.evaluateInputs(task, 2L, 30L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        var owned = new TaskExecutionAccessService.OwnedAttempt(null, task, attempt);
        assertTrue(service.checkWritable(owned, command).accepted());
        var oldRevision = new CollaborationAttemptCommand(20L, "secret", 1L, "host", "agent", "op");
        assertFalse(service.checkWritable(owned, oldRevision).accepted());
        assertNull(attempt.getStopRequestedAt());
        verify(attempts, never()).update(isNull(), any());
    }

    @Test void businessGuardRejectsWrongInstanceOldClaimAndOtherBatch() {
        var batch = new CollaborationBatchRequest("signed-batch", 30L, "device", "/work");
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L), Instant.now().plusSeconds(100));
        when(batches.authorizeTask("signed-batch", 1L, 30L, "device", "/work", 10L)).thenReturn(context);
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task);
        when(attempts.selectByIdForUpdate(20L)).thenReturn(attempt);
        attempt.setBatchId("batch");
        var wrongInstance = new CollaborationAttemptCommand(20L, "secret", 2L, "other", "agent", "op");
        assertThrows(ForbiddenOperationException.class, () -> service.requireOwnedAttempt(1L, batch, 10L, wrongInstance));
        attempt.setBatchId("old-batch");
        assertThrows(ForbiddenOperationException.class, () -> service.requireOwnedAttempt(1L, batch, 10L, command));
        attempt.setBatchId("batch");
        when(credentials.matches("secret", "hash")).thenReturn(false);
        assertThrows(ForbiddenOperationException.class, () -> service.requireOwnedAttempt(1L, batch, 10L, command));
        verify(credentials).matches("secret", "hash");
        verify(attempts, never()).update(isNull(), any());
    }

    @Test void actualExpiredAttemptRecordsStopEvenWhenRequestRevisionIsWrong() {
        attempt.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        var oldRevision = new CollaborationAttemptCommand(20L, "secret", 1L, "host", "agent", "op");
        var result = service.checkWritable(new TaskExecutionAccessService.OwnedAttempt(null, task, attempt), oldRevision);
        assertFalse(result.accepted()); assertEquals("执行租约过期", result.reason());
        assertNotNull(attempt.getStopRequestedAt()); assertEquals("stop_requested", attempt.getStatus());
        verify(attempts).update(isNull(), any());
        verifyNoInteractions(inputs);
    }

    @Test void changedPrerequisiteAttemptOrInputInvalidatesBusinessWrites() {
        attempt.setPrerequisiteVersions(json.createArrayNode().add(json.createObjectNode()
                .put("taskId", 11).put("attemptId", 21).put("inputRevision", 3)));
        var evidence = new TaskCollaborationInputService.PrerequisiteResult(11L, 22L, 3L, json.createObjectNode());
        when(inputs.evaluateInputs(task, 2L, 30L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of(evidence)));
        var result = service.checkWritable(new TaskExecutionAccessService.OwnedAttempt(null, task, attempt), command);
        assertFalse(result.accepted()); assertEquals("前置成果已变化", result.reason());
        assertNotNull(attempt.getStopRequestedAt());
        verify(attempts).update(isNull(), any());
    }

    @Test void recoveryRejectsWrongOwnerDeviceAndClaim() {
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(anyLong(), anyLong(), anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(1L) && invocation.getArgument(2).equals("device") ? binding() : null);
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task);
        when(attempts.selectByIdForUpdate(20L)).thenReturn(attempt);
        assertThrows(ForbiddenOperationException.class, () -> service.requireRecoveryAttempt(2L, 6L, 30L, "device", 10L, command));
        assertThrows(ForbiddenOperationException.class, () -> service.requireRecoveryAttempt(1L, 6L, 30L, "other", 10L, command));
        assertThrows(ForbiddenOperationException.class, () -> service.requireRecoveryAttempt(1L, 6L, 30L, "device", 10L, command));
        verify(credentials).matches("secret", "hash");
    }

    @Test void changedPrerequisiteRevisionAndTaskRevisionRequestStop() {
        attempt.setPrerequisiteVersions(json.createArrayNode().add(json.createObjectNode()
                .put("taskId", 11).put("attemptId", 21).put("inputRevision", 3)));
        var changed = new TaskCollaborationInputService.PrerequisiteResult(11L, 21L, 4L, json.createObjectNode());
        when(inputs.evaluateInputs(task, 2L, 30L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of(changed)));
        var owned = new TaskExecutionAccessService.OwnedAttempt(null, task, attempt);
        assertFalse(service.checkWritable(owned, command).accepted());
        assertEquals("前置成果已变化", attempt.getStopReason());
        attempt.setStopRequestedAt(null); attempt.setStopReason(null); attempt.setStatus("running");
        task.setInputRevision(3L);
        assertFalse(service.checkWritable(owned, command).accepted());
        assertEquals("任务输入版本已变化", attempt.getStopReason());
    }

    private ProjectDeviceBinding binding() {
        ProjectDeviceBinding binding = new ProjectDeviceBinding(); binding.setId(30L); binding.setUserId(1L);
        binding.setProjectId(6L); binding.setDeviceId("device"); return binding;
    }
}
