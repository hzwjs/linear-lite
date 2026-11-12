package com.linearlite.server.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.HostDeviceAuthorizationMapper;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostCollaborationBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private final CollaborationBatchMapper batches = mock(CollaborationBatchMapper.class);
    private final HostDeviceAuthorizationMapper hosts = mock(HostDeviceAuthorizationMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final CollaborationBatchControlService control = mock(CollaborationBatchControlService.class);
    private final CollaborationBatchAuthorizationService authorization = mock(CollaborationBatchAuthorizationService.class);
    private final CollaborationStopConfirmation stopped = new CollaborationStopConfirmation(true, "旧宿主执行与写入进程退出");
    private HostCollaborationBatchService service;
    private CollaborationBatch batch;
    private ProjectDeviceBinding binding;
    private HostDeviceAuthorization grant;
    private CollaborationBatchAuthorizationService.BatchContext context;

    @BeforeEach void setup() {
        service = new HostCollaborationBatchService(batches, hosts, projects, bindings, tasks, attempts,
                access, control, authorization, Clock.fixed(NOW, ZoneOffset.UTC));
        batch = new CollaborationBatch(); batch.setBatchId("batch"); batch.setUserId(1L); batch.setProjectId(6L);
        batch.setBindingId(30L); batch.setBindingRevision(2L); batch.setHostAuthorizationId(9L);
        batch.setHostInstanceId("host"); batch.setRunEpoch(1L); batch.setLifecycle("accepted");
        batch.setDesiredIntent("RUN"); batch.setMaxSlots(2); batch.setMaxAttempts(3); batch.setMaxDurationMs(60000L);
        batch.setRunLeaseUntil(local(NOW.plusSeconds(20))); batch.setDeadlineAt(local(NOW.plusSeconds(60)));
        binding = new ProjectDeviceBinding(); binding.setId(30L); binding.setUserId(1L); binding.setProjectId(6L);
        binding.setDeviceId("device"); binding.setProjectDirectory("/work"); binding.setRevision(2L);
        grant = new HostDeviceAuthorization(); grant.setId(9L); grant.setUserId(1L); grant.setDeviceId("device");
        context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 2L, "device",
                List.of(100L), NOW.plusSeconds(60), 9L, "host", 1L, NOW.plusSeconds(20), NOW.plusSeconds(60), 3, 2);
    }

    @Test void contextUsesOnlyAuthenticatedGrantAndCurrentBinding() {
        when(hosts.selectOwnedForUpdate(1L, 9L)).thenReturn(grant);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device")).thenReturn(binding);
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        when(batches.selectTaskIds("batch")).thenReturn(List.of(100L));
        when(batches.countAttempts("batch")).thenReturn(2L);
        var result = service.context(1L, 9L, "device", 6L, "batch");
        assertEquals("/work", result.projectDirectory());
        assertEquals(List.of(100L), result.taskIds());
        assertEquals(2L, result.attemptsUsed());
        var order = inOrder(hosts, projects, bindings, batches);
        order.verify(hosts).selectOwnedForUpdate(1L, 9L);
        order.verify(projects).lockProject(6L);
        order.verify(bindings).selectForUpdate(1L, 6L, "device");
        order.verify(batches).selectForUpdate("batch");
        batch.setHostAuthorizationId(10L);
        assertThrows(ForbiddenOperationException.class, () -> service.context(1L, 9L, "device", 6L, "batch"));
        batch.setHostAuthorizationId(9L); binding.setRevision(3L);
        assertThrows(ForbiddenOperationException.class, () -> service.context(1L, 9L, "device", 6L, "batch"));
    }

    @Test void acceptedBatchCanBeRetriedOnlyForOriginalInstanceAndEpoch() {
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        when(hosts.selectOwnedForUpdate(1L, 9L)).thenReturn(grant);
        when(bindings.selectForUpdate(1L, 6L, "device")).thenReturn(binding);
        var issued = new CollaborationBatchAuthorizationService.IssuedBatch("batch", "fixture-secret", NOW.plusSeconds(60),
                NOW.plusSeconds(20), NOW.plusSeconds(60), 1L);
        when(authorization.issueAccepted(batch, binding, grant)).thenReturn(issued);
        assertEquals(issued, service.accept(1L, 9L, "device", 6L, "batch", "host", "fixture-proof"));
        assertEquals(issued, service.accept(1L, 9L, "device", 6L, "batch", "host", "fixture-proof"));
        assertThrows(ForbiddenOperationException.class,
                () -> service.accept(1L, 9L, "device", 6L, "batch", "other-host", "fixture-proof"));
        var order = inOrder(authorization);
        order.verify(authorization).verifyPreparedAcceptance("fixture-proof", batch, "device", "host");
        order.verify(authorization).issueAccepted(batch, binding, grant);
        order.verify(authorization).verifyPreparedAcceptance("fixture-proof", batch, "device", "host");
        order.verify(authorization).issueAccepted(batch, binding, grant);
    }

    @Test void finishUnacknowledgedAcceptanceFencesQueuedBatchAndIsReplayable() {
        batch.setLifecycle("queued"); batch.setHostInstanceId(null); batch.setRunEpoch(0L);
        when(authorization.lockPreparedAcceptance("fixture-proof")).thenReturn(batch);
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        when(batches.finishQueuedAcceptance("batch", 0L, local(NOW))).thenAnswer(invocation -> {
            batch.setLifecycle("ended"); batch.setDesiredIntent("STOP"); return 1;
        });
        var first = service.finishAccept("fixture-proof", stopped);
        assertEquals("ended", first.lifecycle());
        assertEquals(first, service.finishAccept("fixture-proof", stopped));
        verify(batches, times(1)).finishQueuedAcceptance("batch", 0L, local(NOW));
        verifyNoInteractions(control);
    }

    @Test void finishUnacknowledgedAcceptanceRefusesAnyAttemptAndKeepsOriginalBudget() {
        when(authorization.lockPreparedAcceptance("fixture-proof")).thenReturn(batch);
        when(batches.countAttempts("batch")).thenReturn(1L, 0L);
        assertThrows(ConflictOperationException.class, () -> service.finishAccept("fixture-proof", stopped));
        verify(batches, never()).finish(any(), any(), any(), any(), any());
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        when(batches.finish("batch", "host", 1L, "host_stopped", local(NOW))).thenAnswer(invocation -> {
            batch.setLifecycle("ended"); batch.setDesiredIntent("STOP"); return 1;
        });
        assertEquals("ended", service.finishAccept("fixture-proof", stopped).lifecycle());
        assertEquals(local(NOW.plusSeconds(60)), batch.getDeadlineAt());
        assertEquals(3, batch.getMaxAttempts());
        verifyNoInteractions(control);
    }

    @Test void runRenewalReturnsStopWithoutExtendingAndCannotExceedDeadline() {
        when(authorization.authorizeHostControl("signed-batch", 1L, 9L, "device")).thenReturn(context);
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        batch.setDeadlineAt(local(NOW.plusSeconds(10)));
        when(batches.renewRunLease(eq("batch"), eq("host"), eq(1L), any(), any())).thenReturn(1);
        var renewed = service.renew(1L, 9L, "device", "signed-batch");
        assertEquals(NOW.plusSeconds(10), renewed.runLeaseUntil());
        batch.setDesiredIntent("STOP");
        var stopping = service.renew(1L, 9L, "device", "signed-batch");
        assertEquals("STOP", stopping.desiredIntent());
        verify(batches, times(1)).renewRunLease(anyString(), anyString(), anyLong(), any(), any());
    }

    @Test void runRenewalReturnsTheDatabaseMicrosecondValue() {
        service = new HostCollaborationBatchService(batches, hosts, projects, bindings, tasks, attempts,
                access, control, authorization, Clock.fixed(NOW.plusNanos(123456789), ZoneOffset.UTC));
        when(authorization.authorizeHostControl("signed-batch", 1L, 9L, "device")).thenReturn(context);
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        when(batches.renewRunLease(eq("batch"), eq("host"), eq(1L), any(), any())).thenReturn(1);
        var renewed = service.renew(1L, 9L, "device", "signed-batch");
        Instant expected = NOW.plusSeconds(30).plusNanos(123456000);
        assertEquals(expected, renewed.runLeaseUntil());
        verify(batches).renewRunLease(eq("batch"), eq("host"), eq(1L), any(), eq(local(expected)));
    }

    @Test void finishRequiresAllAttemptsStoppedAndPreservesPriorStopReason() {
        when(authorization.recoveryUserId("signed-batch")).thenReturn(1L);
        when(authorization.recoveryContext("signed-batch", 1L)).thenReturn(context);
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        when(batches.countUnconfirmedAttempts("batch")).thenReturn(1L, 0L);
        batch.setDesiredIntent("STOP"); batch.setEndReason("host_authorization_revoked");
        assertThrows(ConflictOperationException.class, () -> service.finish("signed-batch", "host_stopped", stopped));
        verify(batches, never()).finish(any(), any(), any(), any(), any());
        when(batches.finish(eq("batch"), eq("host"), eq(1L), eq("host_stopped"), any())).thenAnswer(i -> {
            batch.setLifecycle("ended"); batch.setEndedAt(local(NOW)); return 1;
        });
        var result = service.finish("signed-batch", "host_stopped", stopped);
        assertEquals("ended", result.lifecycle());
        assertEquals("host_authorization_revoked", result.endReason());
        assertEquals(result, service.finish("signed-batch", "host_stopped", stopped));
        verify(batches, times(1)).finish(eq("batch"), eq("host"), eq(1L), eq("host_stopped"), any());
    }

    @Test void completedRequiresCurrentSameBindingDeliveryAndBudgetRequiresEvidence() {
        when(authorization.recoveryUserId("signed-batch")).thenReturn(1L);
        when(authorization.recoveryContext("signed-batch", 1L)).thenReturn(context);
        when(batches.selectForUpdate("batch")).thenReturn(batch);
        Task task = new Task(); task.setId(100L); task.setProjectId(6L); task.setStatus("done"); task.setInputRevision(4L);
        when(tasks.selectByIdForUpdate(100L)).thenReturn(task);
        TaskExecutionAttempt delivery = new TaskExecutionAttempt();
        delivery.setBindingId(31L); delivery.setInputRevision(4L); delivery.setStatus("succeeded");
        delivery.setStopConfirmedAt(local(NOW.minusSeconds(1))); delivery.setResultEvidence(JsonNodeFactory.instance.objectNode());
        when(attempts.selectByTaskId(100L)).thenReturn(List.of(delivery));
        assertThrows(ConflictOperationException.class, () -> service.finish("signed-batch", "completed", stopped));
        assertThrows(ConflictOperationException.class, () -> service.finish("signed-batch", "budget_exhausted", stopped));
        delivery.setBindingId(30L);
        when(batches.finish(eq("batch"), eq("host"), eq(1L), eq("completed"), any())).thenAnswer(i -> {
            batch.setDesiredIntent("STOP"); batch.setLifecycle("ended"); batch.setEndReason("completed");
            batch.setEndedAt(local(NOW)); return 1;
        });
        assertEquals("completed", service.finish("signed-batch", "completed", stopped).endReason());
    }

    @Test void missingStopProofCannotEndAnEmptyAcceptedBatch() {
        assertThrows(IllegalArgumentException.class, () -> service.finish("signed-batch", "host_stopped",
                new CollaborationStopConfirmation(false, "执行仍在运行")));
        verifyNoInteractions(batches);
    }

    private static LocalDateTime local(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
