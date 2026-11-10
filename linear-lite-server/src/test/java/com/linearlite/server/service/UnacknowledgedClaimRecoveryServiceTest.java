package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.entity.*;
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
        var access = new TaskExecutionAccessService(batches, credentials, tasks, attempts, projects, bindings,
                mock(TaskCollaborationInputService.class));
        service = new UnacknowledgedClaimRecoveryService(batches, credentials, access, lifecycle, operations, projects, json);
        when(batches.recoveryContext("signed-batch", 1L)).thenReturn(new CollaborationBatchAuthorizationService.BatchContext(
                "batch", 1L, 6L, 30L, 1L, "dev", List.of(10L), Instant.parse("2020-01-01T00:00:00Z")));
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
        when(lifecycle.confirmStop(eq(1L), eq(6L), eq(30L), eq("dev"), eq(10L), any(), any()))
                .thenReturn(new TaskExecutionLifecycleService.Result(true, null, json.createObjectNode().put("accepted", true)));
        when(lifecycle.readRecovery(eq(1L), eq(6L), eq(30L), eq("dev"), eq(10L), any()))
                .thenReturn(TaskExecutionAttemptResponse.from(attempt));
        var result = recover("host");
        assertTrue(result.accepted()); assertEquals(20L, result.data().path("attemptId").longValue());
        assertNotNull(attempt.getStopRequestedAt());
        assertFalse(result.data().toString().contains(credentials.issue("batch", 30L, 10L, "host", "agent", "claim-original")));
        verify(operations, never()).insert(any(TaskExecutionOperation.class));
    }

    @Test void wrongInstanceMissingReceiptAndMismatchedHashKeepFrozen() {
        assertThrows(ForbiddenOperationException.class, () -> recover("other-host"));
        when(operations.selectForUpdate(6L, 1L, "claim-original")).thenReturn(null);
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        when(operations.selectForUpdate(6L, 1L, "claim-original")).thenReturn(receipt);
        attempt.setClaimTokenHash("wrong");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        verifyNoInteractions(lifecycle);
        verify(attempts, never()).update(any(), any());
    }

    @Test void wrongBindingOwnerOrBatchScopeKeepsFrozen() {
        binding.setUserId(2L);
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        binding.setUserId(1L); attempt.setBatchId("other-batch");
        assertThrows(ForbiddenOperationException.class, () -> recover("host"));
        verifyNoInteractions(lifecycle);
    }

    private TaskExecutionLifecycleService.Result recover(String host) {
        return service.recover(1L, "signed-batch", 30L, "dev", 10L, host, "agent", "claim-original", "recovery-op",
                new CollaborationStopConfirmation(true, "旧宿主进程退出且写门关闭"));
    }
}
