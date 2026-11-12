package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskExecutionCheckpoint;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskExecutionLifecycleServiceTest {
    @Mock TaskExecutionAccessService access;
    @Mock TaskExecutionOperationService operations;
    @Mock TaskExecutionAttemptMapper attempts;
    @Mock ProjectMemberMapper members;
    @Mock TaskStatusService statuses;
    final ObjectMapper json = new ObjectMapper();
    TaskExecutionLifecycleService service;
    CollaborationBatchRequest batch;
    CollaborationAttemptCommand command;
    Task task;
    TaskExecutionAttempt attempt;
    TaskExecutionAccessService.OwnedAttempt owned;
    TaskExecutionAccessService.RecoveryAttempt recovery;

    @BeforeEach void setup() {
        service = new TaskExecutionLifecycleService(access, operations, attempts, members, statuses, json);
        batch = new CollaborationBatchRequest("batch-secret", 30L, "device", "/work");
        command = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "op");
        task = new Task(); task.setId(10L); task.setProjectId(6L); task.setStatus("in_progress");
        task.setInputRevision(2L);
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L); attempt.setBindingId(30L);
        attempt.setInputRevision(2L); attempt.setStatus("running"); attempt.setLeaseUntil(LocalDateTime.now().plusMinutes(2));
        attempt.setCheckpoints(json.createArrayNode());
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L), Instant.now().plusSeconds(300), 11L, "host", 1L,
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(300), 5, 2);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, task, attempt);
        recovery = new TaskExecutionAccessService.RecoveryAttempt(context, task, attempt);
    }

    @Test void renewChangesLeaseWithoutAppendingBusinessCheckpoint() {
        business();
        when(attempts.update(isNull(), any())).thenReturn(1);
        var result = service.renew(1L, batch, 10L, command);
        assertTrue(result.accepted()); assertTrue(result.data().has("leaseUntil"));
        verify(attempts).update(isNull(), any());
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
        verify(operations).save(eq(6L), eq(1L), eq("op"), eq("renew"), eq(20L), any(), any());
    }

    @Test void expiredWriteRejectionPreservesStopResultAndDoesNotRenew() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "执行租约过期", attempt));
        var result = service.renew(1L, batch, 10L, command);
        assertFalse(result.accepted()); assertEquals("执行租约过期", result.reason());
        verify(attempts, never()).update(isNull(), any());
        verify(operations).save(eq(6L), eq(1L), eq("op"), eq("renew"), eq(20L), any(), any());
    }

    @Test void checkpointAppendsOnceAndReplaysOriginalSequenceWithoutCredentialInReceipt() {
        business();
        when(attempts.appendCheckpoint(eq(20L), eq(0), anyString())).thenReturn(1);
        var point = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.of(2026, 11, 10, 12, 0),
                "发现", List.of("docs/a.md"), "等待", List.of(), List.of(), null, null);
        var first = service.checkpoint(1L, batch, 10L, command, point);
        assertTrue(first.accepted()); assertEquals(1, first.data().get("sequence").asInt());
        ArgumentCaptor<JsonNode> requests = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> responses = ArgumentCaptor.forClass(JsonNode.class);
        verify(operations).save(eq(6L), eq(1L), eq("op"), eq("checkpoint"), eq(20L), requests.capture(), responses.capture());
        assertFalse(requests.getValue().toString().contains("secret"));
        assertFalse(responses.getValue().toString().contains("secret"));
        when(operations.findReplay(eq(6L), eq(1L), eq("op"), eq("checkpoint"), any())).thenReturn(responses.getValue());
        assertEquals(first, service.checkpoint(1L, batch, 10L, command, point));
        verify(attempts, times(1)).appendCheckpoint(eq(20L), eq(0), anyString());
        verify(access, times(1)).checkWritable(owned, command);
    }

    @Test void nextCheckpointReceivesNextSequence() {
        business();
        attempt.setCheckpoints(json.createArrayNode().add(json.createObjectNode().put("operationId", "previous")));
        when(attempts.appendCheckpoint(eq(20L), eq(1), anyString())).thenReturn(1);
        var point = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.now(), "发现二", List.of(), null, List.of(), List.of(), null, null);
        var result = service.checkpoint(1L, batch, 10L, command, point);
        assertTrue(result.accepted()); assertEquals(2, result.data().get("sequence").asInt());
        verify(attempts).appendCheckpoint(eq(20L), eq(1), anyString());
    }

    @Test void checkpointPersistsOnlyConsistentFileTraceAndPreviousVersion() {
        business();
        when(attempts.appendCheckpoint(eq(20L), eq(0), anyString())).thenReturn(1);
        var observed = new com.linearlite.server.dto.ArtifactObservation("docs/a.md", "PRESENT", "sha256:" + "b".repeat(64));
        var point = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.now(), null,
                List.of("docs/a.md"), null, List.of(), List.of(observed), "sha256:" + "a".repeat(64), null);
        var result = service.checkpoint(1L, batch, 10L, command, point);
        assertEquals(observed.contentVersion(), result.data().path("checkpoint").path("artifactObservations").get(0).path("contentVersion").asText());
        assertEquals(point.previousContentVersion(), result.data().path("checkpoint").path("previousContentVersion").asText());
        var invalid = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.now(), null,
                List.of("docs/other.md"), null, List.of(), List.of(observed), null, null);
        assertThrows(IllegalArgumentException.class, () -> service.checkpoint(1L, batch, 10L, command, invalid));
    }

    @Test void renameTraceRejectsChangedContentVersion() {
        var observed = new com.linearlite.server.dto.ArtifactObservation("docs/new.md", "PRESENT", "sha256:" + "b".repeat(64));
        var forged = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.now(), null,
                List.of("docs/new.md"), null, List.of(), List.of(observed), "sha256:" + "a".repeat(64), "docs/old.md");
        assertThrows(IllegalArgumentException.class, () -> service.checkpoint(1L, batch, 10L, command, forged));
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
    }

    @Test void checkpointCannotInventContextAdoption() {
        business();
        var ref = new com.linearlite.server.dto.CollaborationContextRef("DOCUMENT", 11L, 3L, null, null);
        var point = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.now(), "发现", List.of(), null, List.of(ref), List.of(), null, null);
        assertThrows(IllegalArgumentException.class, () -> service.checkpoint(1L, batch, 10L, command, point));
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
    }

    @Test void requestStopPreservesOriginalReasonAndReplays() {
        recovery();
        LocalDateTime requested = LocalDateTime.of(2026, 11, 10, 12, 0);
        attempt.setStopRequestedAt(requested); attempt.setStopReason("任务取消"); attempt.setStatus("stop_requested");
        var first = service.requestStop(1L, "batch-secret", 6L, 30L, "device", 10L, command, "执行故障");
        assertTrue(first.accepted()); assertEquals("任务取消", first.data().path("stopReason").asText());
        ArgumentCaptor<JsonNode> response = ArgumentCaptor.forClass(JsonNode.class);
        verify(operations).save(eq(6L), eq(1L), eq("op"), eq("stop_request"), eq(20L), any(), response.capture());
        when(operations.findReplay(eq(6L), eq(1L), eq("op"), eq("stop_request"), any())).thenReturn(response.getValue());
        assertEquals(first, service.requestStop(1L, "batch-secret", 6L, 30L, "device", 10L, command, "执行故障"));
        verify(access, times(1)).requestStop(attempt, "执行故障");
    }

    @Test void confirmedRetryableAttemptMovesTaskToTodo() {
        recovery(); attempt.setStopRequestedAt(LocalDateTime.now().minusMinutes(1)); attempt.setStatus("stop_requested");
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        when(members.selectCount(any())).thenReturn(1L);
        var result = service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command, new CollaborationStopConfirmation(true, "进程退出"));
        assertTrue(result.accepted()); assertEquals("todo", result.data().path("taskStatus").asText());
        ArgumentCaptor<TaskExecutionAttempt> patch = ArgumentCaptor.forClass(TaskExecutionAttempt.class);
        verify(attempts).updateById(patch.capture());
        assertNotNull(patch.getValue().getStopConfirmedAt());
        verify(statuses).updateState(eq(10L), eq("todo"), eq(0), eq(1L), any());
    }

    @Test void canceledTaskDoesNotReenterTodo() {
        recovery(); attempt.setStopRequestedAt(LocalDateTime.now().minusMinutes(1));
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        task.setStatus("canceled");
        assertEquals("canceled", service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command,
                new CollaborationStopConfirmation(true, "进程退出")).data().path("taskStatus").asText());
        verifyNoInteractions(statuses, members);
    }

    @Test void revokedMemberDoesNotReenterTodo() {
        recovery(); attempt.setStopRequestedAt(LocalDateTime.now().minusMinutes(1));
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        when(members.selectCount(any())).thenReturn(0L);
        var result = service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command, new CollaborationStopConfirmation(true, "进程退出"));
        assertTrue(result.accepted()); assertEquals("in_progress", result.data().path("taskStatus").asText());
        verifyNoInteractions(statuses);
    }

    @Test void expiredAttemptCanBeConfirmedAfterMembershipRevocation() {
        recovery(); attempt.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        doAnswer(invocation -> {
            attempt.setStopRequestedAt(LocalDateTime.now()); attempt.setStopReason(invocation.getArgument(1));
            attempt.setStatus("stop_requested"); return null;
        }).when(access).requestStop(eq(attempt), anyString());
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        when(members.selectCount(any())).thenReturn(0L);
        assertTrue(service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command,
                new CollaborationStopConfirmation(true, "进程退出")).accepted());
        assertEquals("执行租约过期", attempt.getStopReason());
        verify(attempts).updateById(any(TaskExecutionAttempt.class));
        verifyNoInteractions(statuses);
    }

    @Test void duplicateAndDoneTasksRetainTheirTerminalStatus() {
        recovery(); attempt.setStopRequestedAt(LocalDateTime.now().minusMinutes(1));
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        for (String terminal : List.of("duplicate", "done")) {
            task.setStatus(terminal);
            var result = service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command,
                    new CollaborationStopConfirmation(true, "进程退出"));
            assertEquals(terminal, result.data().path("taskStatus").asText());
        }
        verifyNoInteractions(statuses, members);
    }

    @Test void oldConfirmedAttemptCannotResetNewAttemptTask() {
        recovery(); attempt.setStopConfirmedAt(LocalDateTime.now().minusMinutes(1));
        attempt.setStopConfirmation(json.createObjectNode().put("detail", "原停止事实"));
        var result = service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command, new CollaborationStopConfirmation(true, "重复回报"));
        assertTrue(result.accepted()); assertEquals("原停止事实", result.data().path("confirmation").path("detail").asText());
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
        verifyNoInteractions(statuses, members);
    }

    @Test void replayedStopConfirmationDoesNotAlterTaskAgain() {
        recovery();
        JsonNode original = json.createObjectNode().put("accepted", true).put("taskStatus", "todo");
        when(operations.findReplay(eq(6L), eq(1L), eq("op"), eq("stop_confirm"), any())).thenReturn(original);
        assertEquals(original, service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command,
                new CollaborationStopConfirmation(true, "进程退出")).data());
        verifyNoInteractions(attempts, statuses, members);
        verify(operations, never()).save(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test void unrequestedActiveAttemptCannotBeConfirmedStopped() {
        recovery();
        var result = service.confirmStop(1L, "batch-secret", 6L, 30L, "device", 10L, command, new CollaborationStopConfirmation(true, "进程退出"));
        assertFalse(result.accepted()); assertEquals("执行尝试缺少停止请求", result.reason());
        verifyNoInteractions(attempts, statuses, members);
    }

    @Test void checkpointRejectsPastedCredentialBeforePersistence() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        var point = new TaskExecutionCheckpoint("op", "agent", LocalDateTime.now(),
                "discovered " + command.claimToken(), List.of("docs/a.md"), null, List.of(), List.of(), null, null);
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.checkpoint(1L, batch, 10L, command, point));
        assertEquals("协作业务材料包含执行凭据", error.getMessage());
        verifyNoInteractions(operations, attempts);
    }

    @Test void recoveryRejectsPastedCredentialInStopReasonAndConfirmation() {
        recovery();
        assertThrows(IllegalArgumentException.class, () -> service.requestStop(
                1L, "batch-secret", 6L, 30L, "device", 10L, command, "reason " + command.claimToken()));
        assertThrows(IllegalArgumentException.class, () -> service.confirmStop(
                1L, "batch-secret", 6L, 30L, "device", 10L, command,
                new CollaborationStopConfirmation(true, "detail " + command.claimToken())));
        verifyNoInteractions(operations, attempts, statuses);
    }

    private void business() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
    }
    private void recovery() {
        when(access.requireRecoveryAttempt(1L, "batch-secret", 6L, 30L, "device", 10L, command)).thenReturn(recovery);
    }
}
