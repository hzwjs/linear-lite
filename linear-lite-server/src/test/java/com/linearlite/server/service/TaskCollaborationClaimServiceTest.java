package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.ArtifactObservation;
import com.linearlite.server.dto.PrerequisiteSourceObservation;
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
            "batch", 8L, 6L, 10L, 1L, "device", List.of(1L), Instant.now().plusSeconds(600), 11L, "host", 1L,
            Instant.now().plusSeconds(30), Instant.now().plusSeconds(600), 5, 2);
    final CollaborationContextService contexts = mock(CollaborationContextService.class);
    final TaskCollaborationDependencyService dependencies = mock(TaskCollaborationDependencyService.class);
    final TaskPrerequisiteReviewService reviews = mock(TaskPrerequisiteReviewService.class);
    TaskCollaborationClaimService service;

    @BeforeEach void setup() {
        service = new TaskCollaborationClaimService(batches, inputs, access, credentials, operations, attempts, tasks, statuses, json, contexts, dependencies, reviews);
        when(batches.observeBatch(any(), eq(8L), eq(10L), any(), any())).thenReturn(context);
        when(batches.hasAttemptBudget(context)).thenReturn(true);
        when(batches.hasExecutionSlot(context)).thenReturn(true);
        when(batches.authorizeTask(any(), eq(8L), eq(10L), any(), any(), eq(1L))).thenReturn(context);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of());
        when(attempts.selectByTaskId(anyLong())).thenReturn(List.of());
        when(credentials.issue(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn("claim-secret");
        when(credentials.hash("claim-secret")).thenReturn("hash");
        when(inputs.evaluateInputs(any(), anyLong(), anyLong(), anyString())).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
    }

    @Test void goalAuthorizationFactBlocksOriginalBatchAfterStopAndCannotSpendAnotherAttempt() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        when(dependencies.goalAuthorizationBlock(6L, 1L, "batch", 3L)).thenReturn("新增业务目标等待授权");
        var observed = service.observe(8L, batch, List.of());
        assertEquals("GOAL_AUTHORIZATION_WAIT", observed.state());
        var claimed = service.claim(8L, batch, 1L, 3L, "host", "agent", "after-stop", List.of());
        assertFalse(claimed.accepted());
        assertEquals("新增业务目标等待授权", claimed.reason());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void candidateRequiresMatchingCurrentObservationAndPinsOnlyAvailableFiles() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        var evidence = json.createObjectNode();
        evidence.putArray("artifactRefs").add("docs/a.md").add("docs/b.md");
        var facts = evidence.putArray("artifactObservations");
        facts.add(json.valueToTree(new ArtifactObservation("docs/a.md", "PRESENT", "sha256:" + "a".repeat(64))));
        facts.add(json.valueToTree(new ArtifactObservation("docs/b.md", "PRESENT", "sha256:" + "b".repeat(64))));
        evidence.putObject("traceOperationIds").put("docs/a.md", "op-a").put("docs/b.md", "op-b");
        var otherEvidence = json.createObjectNode(); otherEvidence.putArray("artifactRefs").add("docs/c.md");
        otherEvidence.putArray("artifactObservations").add(json.valueToTree(new ArtifactObservation("docs/c.md", "PRESENT", "sha256:" + "d".repeat(64))));
        otherEvidence.putObject("traceOperationIds").put("docs/c.md", "op-c");
        var source = new TaskCollaborationInputService.PrerequisiteResult(2L, 20L, 4L, evidence,
                List.of(new TaskCollaborationInputService.TraceCandidate(20L, 4L, evidence),
                        new TaskCollaborationInputService.TraceCandidate(19L, 4L, otherEvidence)));
        when(inputs.evaluateInputs(task, 3L, 10L, "batch")).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(false, TaskCollaborationInputService.TRACE_PENDING, List.of(source)));
        var first = service.observe(8L, batch, List.of());
        assertEquals("DEPENDENCY_WAIT", first.state());
        assertEquals(2, first.tasks().get(0).prerequisites().get(0).evidence().path("artifactObservations").size());
        var missing = new PrerequisiteSourceObservation(20L, 4L,
                List.of(new ArtifactObservation("docs/a.md", "MISSING", null)));
        assertEquals("DEPENDENCY_WAIT", service.observe(8L, batch, List.of(missing)).state());
        var other = new PrerequisiteSourceObservation(19L, 4L,
                List.of(new ArtifactObservation("docs/c.md", "PRESENT", "sha256:" + "d".repeat(64))));
        var fromOther = service.observe(8L, batch, List.of(missing, other));
        assertEquals("READY", fromOther.state());
        assertEquals(19L, fromOther.tasks().get(0).prerequisites().get(0).attemptId());
        var matches = new PrerequisiteSourceObservation(20L, 4L,
                List.of(new ArtifactObservation("docs/a.md", "PRESENT", "sha256:" + "a".repeat(64)),
                        new ArtifactObservation("docs/b.md", "PRESENT", "sha256:" + "c".repeat(64))));
        var observed = service.observe(8L, batch, List.of(matches));
        assertEquals("READY", observed.state());
        var selected = observed.tasks().get(0).prerequisites().get(0).evidence();
        assertEquals(1, selected.path("artifactObservations").size());
        assertEquals("docs/a.md", selected.path("artifactRefs").get(0).asText());
        when(attempts.insert(any(TaskExecutionAttempt.class))).thenAnswer(i -> {
            ((TaskExecutionAttempt) i.getArgument(0)).setId(21L); return 1;
        });
        var claimed = service.claim(8L, batch, 1L, 3L, "host", "agent", "trace-claim", List.of(matches));
        assertTrue(claimed.accepted());
        assertEquals(selected, claimed.prerequisites().get(0).evidence());
        ArgumentCaptor<TaskExecutionAttempt> saved = ArgumentCaptor.forClass(TaskExecutionAttempt.class);
        verify(attempts).insert(saved.capture());
        assertEquals(selected, saved.getValue().getPrerequisiteVersions().get(0).path("evidence"));
    }

    @Test void waitPredicateSkipsEarlierMatchedCandidateAndPinsLaterRelatedTrace() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        var first = json.createObjectNode(); first.putArray("artifactRefs").add("docs/bar.md");
        first.putArray("artifactObservations").add(json.valueToTree(new ArtifactObservation("docs/bar.md", "PRESENT", "sha256:" + "a".repeat(64))));
        first.putObject("traceOperationIds").put("docs/bar.md", "bar");
        var second = json.createObjectNode(); second.putArray("artifactRefs").add("docs/foo.md");
        second.putArray("artifactObservations").add(json.valueToTree(new ArtifactObservation("docs/foo.md", "PRESENT", "sha256:" + "b".repeat(64))));
        second.putObject("traceOperationIds").put("docs/foo.md", "foo");
        var source = new TaskCollaborationInputService.PrerequisiteResult(2L, 20L, 4L, first, List.of(
                new TaskCollaborationInputService.TraceCandidate(20L, 4L, first),
                new TaskCollaborationInputService.TraceCandidate(19L, 4L, second)));
        when(inputs.evaluateInputs(task, 3L, 10L, "batch")).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(false, TaskCollaborationInputService.TRACE_PENDING, List.of(source)));
        when(reviews.blockingReason(eq("batch"), eq(task), anyList(), anyList())).thenAnswer(invocation -> {
            List<TaskCollaborationInputService.PrerequisiteResult> values = invocation.getArgument(2);
            return values.stream().anyMatch(value -> value.attemptId() == 20L) ? "等待相关文件变化" : null;
        });
        var observations = List.of(
                new PrerequisiteSourceObservation(20L, 4L, List.of(new ArtifactObservation("docs/bar.md", "PRESENT", "sha256:" + "a".repeat(64)))),
                new PrerequisiteSourceObservation(19L, 4L, List.of(new ArtifactObservation("docs/foo.md", "PRESENT", "sha256:" + "b".repeat(64)))));
        when(attempts.insert(any(TaskExecutionAttempt.class))).thenAnswer(invocation -> {
            ((TaskExecutionAttempt) invocation.getArgument(0)).setId(21L); return 1;
        });
        var result = service.claim(8L, batch, 1L, 3L, "host", "agent", "pick-related", observations);
        assertTrue(result.accepted());
        assertEquals(19L, result.prerequisites().get(0).attemptId());
    }

    @Test void observationExposesAdoptionAndRelationshipFactsWithoutTreatingLocalAdoptionAsVerification() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        TaskExecutionAttempt previous = attempt(21, 1, 10);
        previous.setStatus("succeeded");
        when(attempts.selectByTaskId(1L)).thenReturn(List.of(previous));
        var ref = new com.linearlite.server.dto.CollaborationContextRef("PROJECT_FILE", null, null, "docs/a.md", "sha256:" + "a".repeat(64));
        when(contexts.project(previous, 6L, java.util.Map.of())).thenReturn(List.of(
                new CollaborationContextService.ContextStatus(ref, null, "PENDING_CONFIRMATION")));
        var observation = service.observe(8L, batch, List.of());
        assertEquals("PENDING_CONFIRMATION", observation.tasks().get(0).contextFacts().get(0).refs().get(0).status());
        verify(dependencies).listFacts(6L, 1L);
    }

    @Test void claimCreatesAttemptAndPersistsNoCredentialInReceipt() {
        Task task = task(1, "todo");
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
        assertEquals("running", saved.getValue().getStatus());
        assertEquals(0, saved.getValue().getLeaseUntil().getNano());
        assertFalse(com.linearlite.server.dto.CollaborationLeaseTime.toInstant(saved.getValue().getLeaseUntil())
                .isAfter(context.runLeaseUntil()));
        verify(statuses).updateState(eq(1L), eq("in_progress"), anyInt(), eq(8L), any());
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> response = ArgumentCaptor.forClass(JsonNode.class);
        verify(operations).save(eq(6L), eq(8L), eq("op"), eq("claim"), eq(21L), request.capture(), response.capture());
        assertFalse(request.getValue().toString().contains("secret"));
        assertFalse(response.getValue().toString().contains("secret"));
    }

    @Test void replayPrecedesAttemptAndSlotBudgetsButNewClaimsRespectBoth() {
        var previous = json.createObjectNode().put("accepted", true).put("attemptId", 21L)
                .put("inputRevision", 3L).put("leaseUntil", Instant.now().plusSeconds(10).toString());
        previous.putArray("prerequisites");
        when(operations.findReplay(eq(6L), eq(8L), eq("same"), eq("claim"), any())).thenReturn(previous);
        var replay = service.claim(8L, batch, 1L, 3L, "host", "agent", "same", List.of());
        assertTrue(replay.accepted());
        verify(batches, never()).hasAttemptBudget(context);
        verify(batches, never()).hasExecutionSlot(context);
        when(batches.hasAttemptBudget(context)).thenReturn(false);
        var exhausted = service.claim(8L, batch, 1L, 3L, "host", "agent", "new-one", List.of());
        assertEquals("批次尝试预算耗尽", exhausted.reason());
        when(batches.hasAttemptBudget(context)).thenReturn(true);
        when(batches.hasExecutionSlot(context)).thenReturn(false);
        var full = service.claim(8L, batch, 1L, 3L, "host", "agent", "new-two", List.of());
        assertEquals("批次执行槽位已满", full.reason());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void taskClaimCannotSubstituteAnotherHostInstance() {
        assertThrows(com.linearlite.server.exception.ForbiddenOperationException.class,
                () -> service.claim(8L, batch, 1L, 3L, "other-host", "agent", "op", List.of()));
        verifyNoInteractions(operations);
    }

    @Test void claimRejectsPastedBatchCredentialInPersistentFields() {
        assertThrows(IllegalArgumentException.class,
                () -> service.claim(8L, batch, 1L, 3L, batch.batchToken(), "agent", "op", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.claim(8L, batch, 1L, 3L, "host", "agent", batch.batchToken(), List.of()));
        verifyNoInteractions(operations);
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void otherTaskDoesNotBlockClaimButSameTaskRemainsExclusive() {
        Task task = task(1, "todo");
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        TaskExecutionAttempt other = attempt(22L, 2L, 10L);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(other));
        when(attempts.insert(any(TaskExecutionAttempt.class))).thenAnswer(i -> {
            ((TaskExecutionAttempt) i.getArgument(0)).setId(21L); return 1;
        });
        assertTrue(service.claim(8L, batch, 1L, 3L, "host", "agent", "op", List.of()).accepted());
        other.setTaskId(1L);
        var occupied = service.claim(8L, batch, 1L, 3L, "host", "agent", "op2", List.of());
        assertFalse(occupied.accepted());
        assertEquals("任务正在执行", occupied.reason());
        other.setBindingId(11L);
        var otherBinding = service.claim(8L, batch, 1L, 3L, "host", "agent", "op-cross-binding", List.of());
        assertFalse(otherBinding.accepted());
        assertEquals("任务正在执行", otherBinding.reason());
        other.setStatus("stop_requested");
        assertEquals("任务等待停止确认", service.claim(8L, batch, 1L, 3L, "host", "agent", "op3", List.of()).reason());
        verify(attempts, times(1)).insert(any(TaskExecutionAttempt.class));
    }

    @Test void deliveredPrerequisiteWithoutLocalArtifactIsReadyForReviewAndTaskOwnershipStillApplies() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        var prerequisite = new TaskCollaborationInputService.PrerequisiteResult(2L, 23L, 4L, json.createObjectNode().put("path", "a"), List.of());
        when(inputs.evaluateInputs(task, 3L, 10L, "batch")).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of(prerequisite)));
        assertEquals("READY", service.observe(8L, batch, List.of()).state());
        var observations = List.of(new com.linearlite.server.dto.PrerequisiteSourceObservation(23L, 4L, List.of(new com.linearlite.server.dto.ArtifactObservation("artifact.txt", "MISSING", null))));
        assertEquals("READY", service.observe(8L, batch, observations).state());
        assertTrue(service.observe(8L, batch, observations).tasks().get(0).prerequisiteReviewStatuses().get(0).adoptionRequired());
        TaskExecutionAttempt other = attempt(22L, 2L, 10L);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(other));
        var waiting = service.observe(8L, batch, observations);
        assertEquals("READY", waiting.state());
        other.setTaskId(1L);
        assertEquals("TASK_WAIT", service.observe(8L, batch, observations).state());
    }

    @Test void persistedReviewBlockDoesNotCreateAnotherAttemptUntilObservedFactsChange() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        when(reviews.blockingReason("batch", task, List.of(), List.of())).thenReturn("等待相关成果变化");
        assertEquals("PREREQUISITE_REVIEW_WAIT", service.observe(8L, batch, List.of()).state());
        assertFalse(service.claim(8L, batch, 1L, 3L, "host", "agent", "blocked", List.of()).accepted());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void activeAttemptShowsAdoptionAndKnownMaterialChangesThroughTheSameEnvironmentStatus() {
        Task task = task(1, "in_progress");
        when(tasks.selectById(1L)).thenReturn(task);
        var source = new TaskCollaborationInputService.PrerequisiteResult(2L, 23L, 4L, json.createObjectNode(), List.of());
        when(inputs.evaluateInputs(task, 3L, 10L, "batch")).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of(source)));
        TaskExecutionAttempt own = attempt(21L, 1L, 10L);
        own.setInputRevision(3L); own.setCheckpoints(json.createArrayNode());
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(own));
        when(attempts.selectByTaskId(1L)).thenReturn(List.of(own));
        var first = service.observe(8L, batch, List.of());
        assertEquals("TASK_WAIT", first.state());
        assertTrue(first.tasks().get(0).prerequisiteReviewStatuses().get(0).adoptionRequired());
        String version = "sha256:" + "a".repeat(64);
        var observation = new com.linearlite.server.dto.ArtifactObservation("src/a", "PRESENT", version);
        var review = new com.linearlite.server.dto.PrerequisiteReviewRequest(2L, 23L, 4L, "ADOPT", "满足当前目标", "读取事实", List.of(observation), null, null);
        var point = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "ADOPT").put("operationId", "review");
        point.set("review", json.valueToTree(review));
        own.setCheckpoints(json.createArrayNode().add(point));
        var missingCurrent = service.observe(8L, batch, List.of()).tasks().get(0).prerequisiteReviewStatuses().get(0);
        assertFalse(missingCurrent.adoptionRequired());
        assertEquals("PENDING_CONFIRMATION", missingCurrent.materialStatus());
        var changed = new com.linearlite.server.dto.ArtifactObservation("src/a", "PRESENT", "sha256:" + "b".repeat(64));
        var current = List.of(new com.linearlite.server.dto.PrerequisiteSourceObservation(23L, 4L, List.of(changed)));
        var stale = service.observe(8L, batch, current).tasks().get(0).prerequisiteReviewStatuses().get(0);
        assertTrue(stale.adoptionRequired());
        assertEquals("REVIEW_REQUIRED", stale.materialStatus());
    }

    @Test void claimReplayUsesSameDerivedTokenWithoutWritingAgain() {
        var replay = json.createObjectNode().put("accepted", true).put("attemptId", 21L).put("inputRevision", 3L)
                .put("leaseUntil", "2026-11-10T12:00:00Z");
        replay.putArray("prerequisites");
        when(operations.findReplay(eq(6L), eq(8L), eq("op"), eq("claim"), any())).thenReturn(replay);
        var result = service.claim(8L, batch, 1L, 3L, "host", "agent", "op", List.of());
        assertEquals("claim-secret", result.claimToken());
        assertEquals(21L, result.attemptId());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
        verify(statuses, never()).updateState(any(), any(), anyInt(), any(), any());
    }

    @Test void differentBindingDoesNotBlockOtherTasksAndStaleRevisionCannotClaim() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(attempt(22L, 2L, 11L)));
        assertEquals("READY", service.observe(8L, batch, List.of()).state());
        var result = service.claim(8L, batch, 1L, 2L, "host", "agent", "old-version", List.of());
        assertFalse(result.accepted());
        assertEquals("任务输入版本已变化", result.reason());
        verify(attempts, never()).insert(any(TaskExecutionAttempt.class));
    }

    @Test void expiredAttemptStopsOnlyItsTaskAndCompleteRequiresSameBindingEvidence() {
        Task task = task(1, "todo");
        when(tasks.selectById(1L)).thenReturn(task);
        TaskExecutionAttempt expired = attempt(22L, 2L, 10L);
        expired.setLeaseUntil(LocalDateTime.now().minusSeconds(1));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(expired));
        assertEquals("READY", service.observe(8L, batch, List.of()).state());
        verify(access).requestStop(expired, "执行租约过期");

        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of());
        task.setStatus("done");
        TaskExecutionAttempt success = attempt(23L, 1L, 11L);
        success.setStatus("succeeded"); success.setInputRevision(3L); success.setStopConfirmedAt(LocalDateTime.now());
        success.setResultEvidence(json.createObjectNode().put("path", "src/a"));
        when(attempts.selectByTaskId(1L)).thenReturn(List.of(success));
        assertEquals("BLOCKED", service.observe(8L, batch, List.of()).state());
        success.setBindingId(10L);
        assertEquals("COMPLETE", service.observe(8L, batch, List.of()).state());
    }

    @Test void serviceEntryPointsUseReadCommittedRollbackTransactions() throws Exception {
        for (String name : List.of("observe", "claim")) {
            var method = java.util.Arrays.stream(TaskCollaborationClaimService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = method.getAnnotation(Transactional.class);
            assertNotNull(tx);
            assertEquals(Isolation.READ_COMMITTED, tx.isolation());
            assertTrue(List.of(tx.rollbackFor()).contains(Exception.class));
        }
    }

    private Task task(long id, String status) {
        Task task = new Task(); task.setId(id); task.setProjectId(6L); task.setStatus(status);
        task.setInputRevision(3L);
        return task;
    }
    private TaskExecutionAttempt attempt(long id, long taskId, long bindingId) {
        TaskExecutionAttempt attempt = new TaskExecutionAttempt(); attempt.setId(id); attempt.setTaskId(taskId);
        attempt.setBindingId(bindingId);
        attempt.setStatus("running"); attempt.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
        return attempt;
    }
}
