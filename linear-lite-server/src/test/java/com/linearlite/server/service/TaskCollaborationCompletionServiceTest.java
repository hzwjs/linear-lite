package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskDeliveryEvidence;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskCollaborationCompletionServiceTest {
    @Mock TaskExecutionAccessService access;
    @Mock TaskExecutionOperationService operations;
    @Mock TaskExecutionAttemptMapper attempts;
    @Mock TaskStatusService statuses;
    @Mock TaskHierarchyCompletionService hierarchy;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    TaskCollaborationCompletionService service;
    CollaborationBatchRequest batch;
    CollaborationAttemptCommand command;
    TaskDeliveryEvidence evidence;
    CollaborationStopConfirmation stopped;
    Task task;
    TaskExecutionAttempt attempt;
    TaskExecutionAccessService.OwnedAttempt owned;

    @BeforeEach void setup() {
        service = new TaskCollaborationCompletionService(access, operations, attempts, statuses, hierarchy, json);
        batch = new CollaborationBatchRequest("batch-secret", 30L, "device", "/work");
        command = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "complete-1");
        evidence = new TaskDeliveryEvidence(List.of(new TaskDeliveryEvidence.CompletionCondition(
                "交付接口", "测试通过", List.of("docs/validation.md"))),
                List.of("src/App.java"), List.of("docs/validation.md"), "git:abc", List.of(), List.of());
        stopped = new CollaborationStopConfirmation(true, "进程退出，写入停止");
        task = new Task(); task.setId(10L); task.setProjectId(6L); task.setStatus("in_progress");
        task.setInputRevision(2L);
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L); attempt.setBindingId(30L);
        attempt.setInputRevision(2L); attempt.setStatus("running");
        attempt.setPrerequisiteVersions(json.createArrayNode());
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L), Instant.now().plusSeconds(300), 11L, "host", 1L,
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(300), 5, 2);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, task, attempt);
    }

    @Test void validDeliverySavesProofStopAndDoneWithAuthoritativeVersions() {
        writable();
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        when(hierarchy.completeEligibleAncestors(eq(10L), eq(1L), any())).thenReturn(List.of());
        var result = service.complete(1L, batch, 10L, command, evidence, stopped);
        assertTrue(result.accepted()); assertEquals("done", result.data().path("status").asText());
        ArgumentCaptor<TaskExecutionAttempt> saved = ArgumentCaptor.forClass(TaskExecutionAttempt.class);
        InOrder order = inOrder(attempts, statuses, operations);
        order.verify(attempts).updateById(saved.capture());
        order.verify(statuses).updateState(eq(10L), eq("done"), eq(100), eq(1L), any());
        order.verify(operations).save(eq(6L), eq(1L), eq("complete-1"), eq("complete"), eq(20L), any(), any());
        TaskExecutionAttempt patch = saved.getValue();
        assertEquals("succeeded", patch.getStatus()); assertNotNull(patch.getStopConfirmedAt());
        assertNotNull(patch.getFinishedAt());
        assertEquals(2L, patch.getResultEvidence().path("inputRevision").asLong());
        assertEquals(attempt.getPrerequisiteVersions(), patch.getResultEvidence().get("prerequisiteVersions"));
        assertFalse(result.data().toString().contains("secret"));
    }

    @Test void completionRequiresCurrentAttemptAdoptionAndFreshObservationForEachSource() {
        writable();
        attempt.setPrerequisiteVersions(json.createArrayNode().add(json.createObjectNode()
                .put("taskId", 9).put("attemptId", 19).put("inputRevision", 7)));
        attempt.setCheckpoints(json.createArrayNode());
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, evidence, stopped));
        var observed = new com.linearlite.server.dto.ArtifactObservation("docs/a.md", "PRESENT", "sha256:" + "a".repeat(64));
        var review = new com.linearlite.server.dto.PrerequisiteReviewRequest(9L, 19L, 7L,
                "ADOPT", "满足本任务", "读取确认", List.of(observed), null, null);
        var fact = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("operationId", "review-1").put("outcome", "ADOPT");
        fact.set("review", json.valueToTree(review)); attempt.setCheckpoints(json.createArrayNode().add(fact));
        var changed = new com.linearlite.server.dto.ArtifactObservation("docs/a.md", "PRESENT", "sha256:" + "b".repeat(64));
        var stale = new TaskDeliveryEvidence(evidence.conditions(), List.of(), List.of(), null, List.of(),
                List.of(new com.linearlite.server.dto.PrerequisiteAdoption("review-1", List.of(changed))));
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, stale, stopped));
        var adopted = new TaskDeliveryEvidence(evidence.conditions(), List.of(), List.of(), null, List.of(),
                List.of(new com.linearlite.server.dto.PrerequisiteAdoption("review-1", List.of(observed))));
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        assertTrue(service.complete(1L, batch, 10L, command, adopted, stopped).accepted());
    }

    @Test void missingEvidenceOrStopConfirmationNeverChangesState() {
        var missingEvidence = new TaskDeliveryEvidence(List.of(), List.of(), List.of("output"), "git:abc", List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, missingEvidence, stopped));
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, evidence,
                new CollaborationStopConfirmation(false, "进程未退出")));
        verifyNoInteractions(access, attempts, statuses, operations);
    }

    @Test void changedPathDoesNotRequireDeclaredScope() {
        writable();
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        var changed = new TaskDeliveryEvidence(evidence.conditions(), List.of("another/Module.java"), evidence.artifactRefs(), "git:abc", List.of(), List.of());
        assertTrue(service.complete(1L, batch, 10L, command, changed, stopped).accepted());
    }

    @Test void readonlyDeliveryCanUseAnEmptyChangeList() {
        writable();
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        var readonly = new TaskDeliveryEvidence(List.of(new TaskDeliveryEvidence.CompletionCondition("分析", "完成", List.of())), List.of(), List.of(), null, List.of(), List.of());
        assertTrue(service.complete(1L, batch, 10L, command, readonly, stopped).accepted());
    }

    @Test void deliveryCannotOmitOrInventAdoptedContexts() {
        writable();
        var document = new com.linearlite.server.dto.CollaborationContextRef("DOCUMENT", 11L, 3L, null, null);
        var point = json.createObjectNode(); point.set("contextRefs", json.valueToTree(List.of(document)));
        attempt.setCheckpoints(json.createArrayNode().add(point));
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, evidence, stopped));
        var invented = new TaskDeliveryEvidence(evidence.conditions(), evidence.changedPaths(), evidence.artifactRefs(),
                evidence.projectCheckpoint(), List.of(new com.linearlite.server.dto.CollaborationContextRef("DOCUMENT", 12L, 3L, null, null)), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, invented, stopped));
        var included = new TaskDeliveryEvidence(evidence.conditions(), evidence.changedPaths(), evidence.artifactRefs(),
                evidence.projectCheckpoint(), List.of(document), List.of());
        when(attempts.updateById(any(TaskExecutionAttempt.class))).thenReturn(1);
        assertTrue(service.complete(1L, batch, 10L, command, included, stopped).accepted());
    }

    @Test void artifactReferencesMustBeNormalizedProjectRelativePaths() {
        for (List<String> paths : List.of(List.of("../outside"), List.of("/tmp/output"),
                List.of("docs/../output"), List.of("docs/output", "docs/output/"))) {
            var invalid = new TaskDeliveryEvidence(evidence.conditions(), List.of(), paths, "git:abc", List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, invalid, stopped));
        }
        verifyNoInteractions(access, attempts, statuses, operations);
    }

    @Test void rejectedCurrentInputPreservesStopResultWithoutDone() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "输入失效", attempt));
        var result = service.complete(1L, batch, 10L, command, evidence, stopped);
        assertFalse(result.accepted()); assertEquals("输入失效", result.reason());
        verify(operations).save(eq(6L), eq(1L), eq("complete-1"), eq("complete"), eq(20L), any(), any());
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
        verifyNoInteractions(statuses);
    }

    @Test void sameOperationReplaysOriginalWithoutCheckingExpiredWritePermission() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        JsonNode original = json.createObjectNode().put("accepted", true).put("status", "done").put("attemptId", 20);
        when(operations.findReplay(eq(6L), eq(1L), eq("complete-1"), eq("complete"), any())).thenReturn(original);
        var result = service.complete(1L, batch, 10L, command, evidence, stopped);
        assertTrue(result.accepted()); assertEquals(original, result.data());
        verify(access, never()).checkWritable(any(), any());
        verifyNoInteractions(attempts, statuses, hierarchy);
    }

    @Test void unownedAttemptIsRejectedBeforeReceiptOrState() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenThrow(new ForbiddenOperationException("归属不匹配"));
        assertThrows(ForbiddenOperationException.class, () -> service.complete(1L, batch, 10L, command, evidence, stopped));
        verifyNoInteractions(operations, attempts, statuses);
    }

    @Test void credentialsInEvidenceOrOperationIdAreRejectedBeforeReceipt() {
        when(access.requireOwnedAttempt(eq(1L), eq(batch), eq(10L), any())).thenReturn(owned);
        var contaminated = new TaskDeliveryEvidence(List.of(new TaskDeliveryEvidence.CompletionCondition(
                "交付接口", "claim-secret", List.of("proof"))), List.of(), List.of("proof"), "git:abc", List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, command, contaminated, stopped));
        var badOperation = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "batch-secret");
        assertThrows(IllegalArgumentException.class, () -> service.complete(1L, batch, 10L, badOperation, evidence, stopped));
        verifyNoInteractions(operations, attempts, statuses);
    }

    @Test void completionUsesRollbackForExceptionsAndReadCommitted() throws Exception {
        Transactional tx = TaskCollaborationCompletionService.class.getMethod("complete", Long.class,
                CollaborationBatchRequest.class, Long.class, CollaborationAttemptCommand.class,
                TaskDeliveryEvidence.class, CollaborationStopConfirmation.class).getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, tx.isolation());
        assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor());
    }

    private void writable() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
    }
}
