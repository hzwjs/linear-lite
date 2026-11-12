package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.entity.*;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnacknowledgedClaimRecoveryServiceTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final CollaborationBatchAuthorizationService batches = mock(CollaborationBatchAuthorizationService.class);
    final ProjectMapper projects = mock(ProjectMapper.class);
    final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    final TaskMapper tasks = mock(TaskMapper.class);
    final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    final TaskExecutionOperationMapper operations = mock(TaskExecutionOperationMapper.class);
    final TaskExecutionLifecycleService lifecycle = mock(TaskExecutionLifecycleService.class);
    TaskExecutionClaimCredentialService credentials;
    UnacknowledgedClaimRecoveryService service;
    TaskExecutionAttempt attempt;
    ProjectDeviceBinding binding;
    TaskExecutionOperation receipt;

    @BeforeEach void setup() {
        JwtProperties jwt = new JwtProperties(); jwt.setSecret("unit-test-unacknowledged-claim-signing-key");
        credentials = new TaskExecutionClaimCredentialService(jwt, json);
        var access = new TaskExecutionAccessService(batches, credentials, tasks, attempts, mock(TaskCollaborationInputService.class));
        service = new UnacknowledgedClaimRecoveryService(batches, credentials, access, lifecycle, operations, projects, json);
        when(batches.recoveryContext("signed-batch", 1L)).thenReturn(new CollaborationBatchAuthorizationService.BatchContext(
                "batch", 1L, 6L, 30L, 1L, "dev", List.of(10L), Instant.parse("2020-01-01T00:00:00Z"), 11L, "host", 1L,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 5, 2));
        when(projects.lockProject(6L)).thenReturn(6L);
        binding = new ProjectDeviceBinding(); binding.setId(30L); binding.setProjectId(6L); binding.setUserId(1L); binding.setDeviceId("dev");
        when(bindings.selectForUpdate(1L, 6L, "dev")).thenReturn(binding);
        Task task = new Task(); task.setId(10L); task.setProjectId(6L); task.setInputRevision(2L);
        when(tasks.selectByIdForUpdate(10L)).thenReturn(task);
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L); attempt.setBindingId(30L);
        attempt.setBatchId("batch"); attempt.setHostInstanceId("host"); attempt.setAgentInstanceId("agent"); attempt.setInputRevision(2L);
        attempt.setClaimTokenHash(credentials.hash(credentials.issue("batch", 30L, 10L, "host", "agent", "claim-original")));
        when(attempts.selectByIdForUpdate(20L)).thenReturn(attempt);
        receipt = new TaskExecutionOperation(); receipt.setKind("claim"); receipt.setAttemptId(20L);
        receipt.setRequestJson("{\"batchId\":\"batch\",\"bindingId\":30,\"taskId\":10,\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"inputRevision\":2}");
        receipt.setResponseJson("{\"accepted\":true,\"attemptId\":20}");
        when(operations.selectForUpdate(6L, 1L, "claim-original")).thenReturn(receipt);
    }

    @Test void originalReceiptReconstructsOnlyStopAuthorityAndNeverReturnsToken() {
        when(lifecycle.confirmStop(eq(1L), eq("signed-batch"), eq(6L), eq(30L), eq("dev"), eq(10L), any(), any()))
                .thenReturn(new TaskExecutionLifecycleService.Result(true, null, json.createObjectNode().put("accepted", true)));
        when(lifecycle.readRecovery(eq(1L), eq("signed-batch"), eq(6L), eq(30L), eq("dev"), eq(10L), any()))
                .thenReturn(TaskExecutionAttemptResponse.from(attempt));
        var result = recover("host");
        assertTrue(result.accepted()); assertEquals(20L, result.data().path("attemptId").longValue());
        assertEquals("CLAIMED", result.data().path("claimOutcome").textValue());
        assertNotNull(attempt.getStopRequestedAt());
        assertFalse(result.data().toString().contains(credentials.issue("batch", 30L, 10L, "host", "agent", "claim-original")));
        verify(operations, never()).insert(any(TaskExecutionOperation.class));
    }

    @Test void wrongInstanceAndMismatchedHashKeepFrozen() {
        assertThrows(ForbiddenOperationException.class, () -> recover("other-host"));
        attempt.setClaimTokenHash("wrong");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        verifyNoInteractions(lifecycle);
        verify(attempts, never()).update(any(), any());
    }

    @Test void absentReceiptIsFencedAndLateClaimCannotReuseItsOperationId() {
        when(operations.selectForUpdate(6L, 1L, "claim-original")).thenReturn(null);
        when(operations.insert(any(TaskExecutionOperation.class))).thenReturn(1);
        var result = recover("host");
        assertTrue(result.accepted());
        assertTrue(result.data().path("attemptId").isNull());
        assertEquals("FENCED", result.data().path("claimOutcome").textValue());
        var captured = org.mockito.ArgumentCaptor.forClass(TaskExecutionOperation.class);
        verify(operations).insert(captured.capture());
        TaskExecutionOperation fence = captured.getValue();
        assertEquals("claim-original", fence.getOperationId());
        assertEquals("claim_recovery_fence", fence.getKind());
        assertFalse(fence.getRequestJson().contains("signed-batch"));
        assertFalse(fence.getResponseJson().contains("signed-batch"));
        var order = inOrder(projects, operations);
        order.verify(projects).lockProject(6L);
        order.verify(operations).selectForUpdate(6L, 1L, "claim-original");
        order.verify(operations).insert(any(TaskExecutionOperation.class));
        when(operations.selectForUpdate(6L, 1L, "claim-original")).thenReturn(fence);
        assertEquals("FENCED", recover("host").data().path("claimOutcome").textValue());
        assertThrows(ForbiddenOperationException.class, () -> service.recover(1L, "signed-batch", 30L, "dev", 10L,
                3L, "host", "agent", "claim-original", "recovery-op", new CollaborationStopConfirmation(true, "进程退出")));
        var lateClaim = json.createObjectNode().put("batchId", "batch").put("bindingId", 30)
                .put("taskId", 10).put("inputRevision", 2).put("hostInstanceId", "host").put("agentInstanceId", "agent");
        lateClaim.putArray("sourceObservations");
        var replay = new TaskExecutionOperationService(operations, json);
        assertThrows(ConflictOperationException.class,
                () -> replay.findReplay(6L, 1L, "claim-original", "claim", lateClaim));
        verify(operations, times(1)).insert(any(TaskExecutionOperation.class));
        verifyNoInteractions(lifecycle, attempts);
    }

    @Test void rejectedReceiptClosesUnknownClaimOnlyForOriginalIdentity() {
        receipt.setAttemptId(null);
        receipt.setResponseJson("{\"accepted\":false,\"reason\":\"任务正在执行\"}");
        var result = recover("host");
        assertTrue(result.accepted());
        assertEquals("REJECTED", result.data().path("claimOutcome").textValue());
        assertTrue(result.data().path("attemptId").isNull());
        assertEquals("REJECTED", recover("host").data().path("claimOutcome").textValue());
        assertThrows(ForbiddenOperationException.class, () -> service.recover(1L, "signed-batch", 30L, "dev", 10L,
                2L, "host", "other-agent", "claim-original", "recovery-op", new CollaborationStopConfirmation(true, "进程退出")));
        assertThrows(ForbiddenOperationException.class, () -> service.recover(1L, "signed-batch", 30L, "dev", 10L,
                3L, "host", "agent", "claim-original", "recovery-op", new CollaborationStopConfirmation(true, "进程退出")));
        verify(operations, never()).insert(any(TaskExecutionOperation.class));
        verifyNoInteractions(lifecycle, attempts);
    }

    @Test void missingStopConfirmationNeverCreatesFence() {
        when(operations.selectForUpdate(6L, 1L, "claim-original")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.recover(1L, "signed-batch", 30L, "dev", 10L,
                2L, "host", "agent", "claim-original", "recovery-op", new CollaborationStopConfirmation(false, "未退出")));
        assertThrows(IllegalArgumentException.class, () -> service.recover(1L, "signed-batch", 30L, "dev", 10L,
                2L, "host", "agent", "claim-original", "recovery-op", new CollaborationStopConfirmation(true, " ")));
        verifyNoInteractions(projects, operations, lifecycle, attempts);
    }

    @Test void malformedOrOtherOperationReceiptDoesNotAuthorizeRecovery() {
        receipt.setKind("other");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        receipt.setKind("claim");
        receipt.setAttemptId(null);
        receipt.setResponseJson("{}");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        receipt.setResponseJson("{\"accepted\":false}");
        receipt.setRequestJson("{\"batchId\":\"other-batch\",\"bindingId\":30,\"taskId\":10,\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"inputRevision\":2}");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        verifyNoInteractions(lifecycle, attempts);
    }

    @Test void wrongBindingOwnerOrBatchScopeKeepsFrozen() {
        when(batches.recoveryContext("signed-batch", 1L)).thenThrow(new ForbiddenOperationException("denied"));
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        doReturn(new CollaborationBatchAuthorizationService.BatchContext(
                "batch", 1L, 6L, 30L, 1L, "dev", List.of(10L), Instant.parse("2020-01-01T00:00:00Z"),
                11L, "host", 1L, Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 5, 2))
                .when(batches).recoveryContext("signed-batch", 1L);
        attempt.setBatchId("other-batch");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        verifyNoInteractions(lifecycle);
    }

    private TaskExecutionLifecycleService.Result recover(String host) {
        return service.recover(1L, "signed-batch", 30L, "dev", 10L, 2L, host, "agent", "claim-original", "recovery-op",
                new CollaborationStopConfirmation(true, "旧宿主进程退出且写门关闭"));
    }
}
