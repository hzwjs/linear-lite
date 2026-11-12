package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.ArtifactObservation;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.PrerequisiteReviewRequest;
import com.linearlite.server.dto.PrerequisiteSourceObservation;
import com.linearlite.server.dto.PrerequisiteAdoption;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskPrerequisiteReviewServiceTest {
    final TaskExecutionAccessService access = mock(TaskExecutionAccessService.class);
    final TaskCollaborationInputService inputs = mock(TaskCollaborationInputService.class);
    final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final CollaborationBatchRequest batch = new CollaborationBatchRequest("batch-secret", 30L, "device", "/work");
    final CollaborationAttemptCommand command = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "review-1");
    final ArtifactObservation missing = new ArtifactObservation("docs/result.md", "MISSING", null);
    final ArtifactObservation present = new ArtifactObservation("docs/result.md", "PRESENT", "sha256:" + "a".repeat(64));
    TaskPrerequisiteReviewService service;
    Task task;
    TaskExecutionAttempt attempt;
    TaskExecutionAccessService.OwnedAttempt owned;
    TaskCollaborationInputService.PrerequisiteResult source;

    @BeforeEach void setup() {
        service = new TaskPrerequisiteReviewService(access, inputs, operations, attempts, json);
        task = new Task(); task.setId(10L); task.setProjectId(6L); task.setInputRevision(2L); task.setStatus("in_progress");
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L); attempt.setBindingId(30L);
        attempt.setBatchId("batch"); attempt.setInputRevision(2L); attempt.setCheckpoints(json.createArrayNode());
        attempt.setPrerequisiteVersions(json.createArrayNode().add(json.createObjectNode()
                .put("taskId", 9).put("attemptId", 19).put("inputRevision", 7)));
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L, 9L), Instant.now().plusSeconds(300), 11L, "host", 1L,
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(300), 5, 2);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, task, attempt);
        var evidence = json.createObjectNode(); evidence.putArray("artifactRefs").add("docs/result.md");
        source = new TaskCollaborationInputService.PrerequisiteResult(9L, 19L, 7L, evidence, List.of());
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
        when(inputs.evaluateInputs(task, 2L, 30L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of(source)));
        when(attempts.appendCheckpoint(eq(20L), eq(0), anyString())).thenReturn(1);
    }

    @Test void missingArtifactCanBeRecordedForInScopeRepairWithoutChangingInputsOrStopping() throws Exception {
        var result = service.review(1L, batch, 10L, command, review("REPAIR_IN_SCOPE", List.of(missing)));
        assertTrue(result.path("accepted").asBoolean());
        assertEquals("REPAIR_IN_SCOPE", result.path("outcome").asText());
        var fact = savedFact();
        assertEquals("MISSING", fact.path("review").path("observations").get(0).path("state").asText());
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
        verify(access, never()).requestStop(any(), anyString());
        verify(operations).save(eq(6L), eq(1L), eq("review-1"), eq("prerequisite_review"), eq(20L), any(), any());
    }

    @Test void adoptionRecordsSourceAndReadEvidenceAndProjectsTheSameFact() throws Exception {
        var result = service.review(1L, batch, 10L, command, review("ADOPT", List.of(present)));
        assertEquals("ADOPT", result.path("outcome").asText());
        attempt.setCheckpoints(json.createArrayNode().add(savedFact()));
        var projected = service.project(attempt);
        assertEquals(1, projected.size());
        assertEquals(19L, projected.get(0).review().sourceAttemptId());
        assertEquals(present, projected.get(0).review().observations().get(0));
    }

    @Test void ownEditVersionChainAllowsDeliveryAndMissingLinkRejectsIt() {
        var reviewed = review("ADOPT", List.of(present));
        var fact = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW")
                .put("operationId", "review-1").put("outcome", "ADOPT");
        fact.set("review", json.valueToTree(reviewed));
        var editOne = json.createObjectNode().put("operationId", "edit-1")
                .put("previousContentVersion", "sha256:" + "a".repeat(64));
        editOne.putArray("artifactObservations").add(json.createObjectNode().put("path", "docs/result.md")
                .put("state", "PRESENT").put("contentVersion", "sha256:" + "b".repeat(64)));
        var editTwo = json.createObjectNode().put("operationId", "edit-2")
                .put("previousContentVersion", "sha256:" + "b".repeat(64));
        editTwo.putArray("artifactObservations").add(json.createObjectNode().put("path", "docs/result.md")
                .put("state", "PRESENT").put("contentVersion", "sha256:" + "c".repeat(64)));
        attempt.setCheckpoints(json.createArrayNode().add(fact).add(editOne).add(editTwo));
        var current = new ArtifactObservation("docs/result.md", "PRESENT", "sha256:" + "c".repeat(64));
        var adoption = new PrerequisiteAdoption("review-1", List.of(current));
        assertDoesNotThrow(() -> TaskPrerequisiteReviewService.requireAdopted(attempt, List.of(adoption)));
        attempt.setCheckpoints(json.createArrayNode().add(fact).add(editTwo));
        assertThrows(IllegalArgumentException.class, () -> TaskPrerequisiteReviewService.requireAdopted(attempt, List.of(adoption)));
        attempt.setCheckpoints(json.createArrayNode().add(editOne).add(fact).add(editTwo));
        assertThrows(IllegalArgumentException.class, () -> TaskPrerequisiteReviewService.requireAdopted(attempt, List.of(adoption)));
    }

    @Test void ownedRenameUsesRealDestinationAndRejectsBrokenVersionChain() {
        var fact = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW")
                .put("operationId", "review-1").put("outcome", "ADOPT");
        fact.set("review", json.valueToTree(review("ADOPT", List.of(present))));
        var renamed = json.createObjectNode().put("operationId", "move-1")
                .put("previousPath", "docs/result.md").put("previousContentVersion", present.contentVersion());
        renamed.putArray("artifactObservations").add(json.createObjectNode().put("path", "docs/renamed.md")
                .put("state", "PRESENT").put("contentVersion", present.contentVersion()));
        attempt.setCheckpoints(json.createArrayNode().add(fact).add(renamed));
        var current = new ArtifactObservation("docs/renamed.md", "PRESENT", present.contentVersion());
        assertDoesNotThrow(() -> TaskPrerequisiteReviewService.requireAdopted(attempt,
                List.of(new PrerequisiteAdoption("review-1", List.of(current)))));
        assertThrows(IllegalArgumentException.class, () -> TaskPrerequisiteReviewService.requireAdopted(attempt,
                List.of(new PrerequisiteAdoption("review-1", List.of(present)))));
        renamed.put("previousContentVersion", "sha256:" + "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> TaskPrerequisiteReviewService.requireAdopted(attempt,
                List.of(new PrerequisiteAdoption("review-1", List.of(current)))));
    }

    @Test void mergedAdoptionAllowsOwnedEditOfFirstFileAndNewReadOfSecond() {
        var originalSecond = new ArtifactObservation("docs/second.md", "PRESENT", "sha256:" + "d".repeat(64));
        var changedFirst = new ArtifactObservation("docs/result.md", "PRESENT", "sha256:" + "b".repeat(64));
        var evidence = json.createObjectNode();
        evidence.putArray("artifactObservations").add(json.valueToTree(present)).add(json.valueToTree(originalSecond));
        var pinned = json.createObjectNode().put("taskId", 9).put("attemptId", 19).put("inputRevision", 7);
        pinned.set("evidence", evidence);
        attempt.setPrerequisiteVersions(json.createArrayNode().add(pinned));
        var previousReview = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW")
                .put("operationId", "old-review").put("outcome", "ADOPT");
        previousReview.set("review", json.valueToTree(review("ADOPT", List.of(present))));
        var edit = json.createObjectNode().put("operationId", "edit-1")
                .put("previousContentVersion", present.contentVersion());
        edit.putArray("artifactObservations").add(json.valueToTree(changedFirst));
        attempt.setCheckpoints(json.createArrayNode().add(previousReview).add(edit));
        when(attempts.appendCheckpoint(eq(20L), eq(2), anyString())).thenReturn(1);
        var merged = service.review(1L, batch, 10L, command, review("ADOPT", List.of(changedFirst, originalSecond)));
        assertEquals("ADOPT", merged.path("outcome").asText());
        attempt.setCheckpoints(json.createArrayNode().add(previousReview));
        assertThrows(ConflictOperationException.class,
                () -> service.review(1L, batch, 10L, command, review("ADOPT", List.of(changedFirst, originalSecond))));
    }

    @Test void inBatchReworkInvalidatesUpstreamAndDownstreamThroughInputService() throws Exception {
        when(inputs.reopenForRework(6L, 9L, 1L)).thenReturn(true);
        var result = service.review(1L, batch, 10L, command, review("REQUEST_REWORK", List.of(missing)));
        assertEquals("REWORK_REQUESTED", result.path("outcome").asText());
        assertTrue(result.path("stopRequested").asBoolean());
        verify(inputs).reopenForRework(6L, 9L, 1L);
        assertEquals("REQUEST_REWORK", savedFact().path("review").path("decision").asText());
    }

    @Test void unfinishedSourceCanReceiveReworkFindingWithoutConflicting() {
        when(inputs.reopenForRework(6L, 9L, 1L)).thenReturn(false);
        var result = service.review(1L, batch, 10L, command, review("REQUEST_REWORK", List.of(missing)));
        assertEquals("WAIT_FOR_CHANGE", result.path("outcome").asText());
        assertTrue(result.path("stopRequested").asBoolean());
        verify(access).requestStop(attempt, "前置成果等待变化");
    }

    @Test void unfinishedSourceReworkRequiresObservablePath() {
        when(inputs.reopenForRework(6L, 9L, 1L)).thenReturn(false);
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.review(1L, batch, 10L, command, review("REQUEST_REWORK", List.of())));
        assertTrue(error.getMessage().contains("待观察路径"));
        verify(access, never()).requestStop(any(), anyString());
    }

    @Test void directoryArtifactMayBeReviewedUsingHostManifestVersion() throws Exception {
        var evidence = json.createObjectNode(); evidence.putArray("artifactRefs").add("dist/");
        source = new TaskCollaborationInputService.PrerequisiteResult(9L, 19L, 7L, evidence, List.of());
        when(inputs.evaluateInputs(task, 2L, 30L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of(source)));
        var manifest = new ArtifactObservation("dist/", "PRESENT", "sha256:" + "b".repeat(64));
        var result = service.review(1L, batch, 10L, command, review("ADOPT", List.of(manifest)));
        assertEquals("ADOPT", result.path("outcome").asText());
        assertEquals("dist/", savedFact().path("review").path("observations").get(0).path("path").asText());
        TaskPrerequisiteReviewService.validateObservations(List.of(new ArtifactObservation("dist/", "MISSING", null)));
    }

    @Test void batchOutsideReworkStopsAndRecordsBusinessAuthorizationWithoutChangingRelations() throws Exception {
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L), Instant.now().plusSeconds(300), 11L, "host", 1L,
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(300), 5, 2);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, task, attempt);
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
        var value = new PrerequisiteReviewRequest(9L, 19L, 7L, "REQUEST_REWORK", "成果缺失", "文件不存在",
                List.of(missing), "所需业务目标", "影响当前交付");
        var result = service.review(1L, batch, 10L, command, value);
        assertEquals("GOAL_AUTHORIZATION_REQUIRED", result.path("outcome").asText());
        assertTrue(result.path("guidance").asText().contains("当前目标内"));
        verify(access).requestStop(attempt, "前置成果需业务授权");
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
        attempt.setCheckpoints(json.createArrayNode().add(savedFact()));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of()));
    }

    @Test void newVersionOrUnrelatedSourceCannotBeReviewedUsingOldClaim() {
        attempt.setPrerequisiteVersions(json.createArrayNode().add(json.createObjectNode()
                .put("taskId", 9).put("attemptId", 21).put("inputRevision", 8)));
        assertThrows(ConflictOperationException.class, () -> service.review(1L, batch, 10L, command, review("ADOPT", List.of(present))));
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
    }

    @Test void ownershipAndStaleExecutionRejectBeforeFactsOrRework() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenThrow(new ForbiddenOperationException("归属不匹配"));
        assertThrows(ForbiddenOperationException.class, () -> service.review(1L, batch, 10L, command, review("REQUEST_REWORK", List.of(missing))));
        verifyNoInteractions(operations);
        reset(access);
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "前置成果已变化", attempt));
        var result = service.review(1L, batch, 10L, command, review("REQUEST_REWORK", List.of(missing)));
        assertFalse(result.path("accepted").asBoolean());
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
    }

    @Test void identicalOperationReplaysEvenAfterAttemptStopsWithoutChangingStateAgain() {
        var replay = json.createObjectNode().put("accepted", true).put("outcome", "REWORK_REQUESTED");
        when(operations.findReplay(eq(6L), eq(1L), eq("review-1"), eq("prerequisite_review"), any())).thenReturn(replay);
        assertEquals(replay, service.review(1L, batch, 10L, command, review("REQUEST_REWORK", List.of(missing))));
        verify(access, never()).checkWritable(any(), any());
        verifyNoInteractions(attempts);
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
    }

    @Test void waitForRelatedFactStopsAndDoesNotRetryUntilObservedFactChanges() throws Exception {
        var result = service.review(1L, batch, 10L, command, review("WAIT_FOR_CHANGE", List.of(missing)));
        assertEquals("WAIT_FOR_CHANGE", result.path("outcome").asText());
        verify(access).requestStop(attempt, "前置成果等待变化");
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
        attempt.setCheckpoints(json.createArrayNode().add(savedFact()));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of()));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(missing)))));
        assertNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(present)))));
    }

    @Test void emptyWaitIsRejectedWithActionableReason() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.review(1L, batch, 10L, command, review("WAIT_FOR_CHANGE", List.of())));
        assertTrue(error.getMessage().contains("待观察路径"));
    }

    @Test void onlyLatestWaitForSameSourceWithinBatchIsEvaluated() {
        var oldWait = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "WAIT_FOR_CHANGE");
        oldWait.set("review", json.valueToTree(review("WAIT_FOR_CHANGE", List.of(present))));
        attempt.setCheckpoints(json.createArrayNode().add(oldWait));
        var newer = new TaskExecutionAttempt(); newer.setId(21L); newer.setTaskId(10L);
        newer.setBatchId("batch"); newer.setInputRevision(2L);
        var newWait = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "WAIT_FOR_CHANGE");
        newWait.set("review", json.valueToTree(review("WAIT_FOR_CHANGE", List.of(missing))));
        newer.setCheckpoints(json.createArrayNode().add(newWait));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt, newer));
        assertNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(present)))));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(missing)))));
        newer.setBatchId("different-batch");
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(present)))));
    }

    @Test void laterAdoptionSupersedesEarlierWaitForSameSource() {
        var oldWait = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "WAIT_FOR_CHANGE");
        oldWait.set("review", json.valueToTree(review("WAIT_FOR_CHANGE", List.of(present))));
        var adoption = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "ADOPT");
        adoption.set("review", json.valueToTree(review("ADOPT", List.of(present))));
        attempt.setCheckpoints(json.createArrayNode().add(oldWait).add(adoption));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt));
        assertNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(present)))));
    }

    @Test void fileAndDirectoryObservationsAtTheSameNameRemainDistinct() throws Exception {
        var file = new ArtifactObservation("dist", "PRESENT", "sha256:" + "a".repeat(64));
        var missingFile = new ArtifactObservation("dist", "MISSING", null);
        var directory = new ArtifactObservation("dist/", "PRESENT", "sha256:" + "b".repeat(64));
        service.review(1L, batch, 10L, command, review("WAIT_FOR_CHANGE", List.of(file)));
        attempt.setCheckpoints(json.createArrayNode().add(savedFact()));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt));
        assertNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(missingFile, directory)))));
        assertThrows(IllegalArgumentException.class,
                () -> TaskPrerequisiteReviewService.validateObservations(List.of(file, missingFile)));
    }

    @Test void statusDistinguishesAdoptionContentChangesAndMissingCurrentEvidence() {
        assertTrue(TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(), List.of(), List.of()).isEmpty());
        var initial = TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(source), List.of(), List.of()).get(0);
        assertTrue(initial.adoptionRequired()); assertEquals("REVIEW_REQUIRED", initial.materialStatus());
        attempt.setStatus("running");
        var fact = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("operationId", "adopt").put("outcome", "ADOPT");
        fact.set("review", json.valueToTree(review("ADOPT", List.of(present, new ArtifactObservation("docs/second.md", "MISSING", null)))));
        attempt.setCheckpoints(json.createArrayNode().add(fact));
        var unknown = TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(source), List.of(attempt), List.of()).get(0);
        assertFalse(unknown.adoptionRequired()); assertEquals("PENDING_CONFIRMATION", unknown.materialStatus());
        var changed = new ArtifactObservation("docs/result.md", "PRESENT", "sha256:" + "b".repeat(64));
        var changedWithMissingCoverage = TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(source), List.of(attempt),
                List.of(sourceObs(List.of(changed)))).get(0);
        assertTrue(changedWithMissingCoverage.adoptionRequired()); assertEquals("REVIEW_REQUIRED", changedWithMissingCoverage.materialStatus());
        var other = new ArtifactObservation("docs/unrelated.md", "PRESENT", "sha256:" + "b".repeat(64));
        var unchanged = TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(source), List.of(attempt), List.of(sourceObs(List.of(
                present, new ArtifactObservation("docs/second.md", "MISSING", null), other)))).get(0);
        assertFalse(unchanged.adoptionRequired()); assertEquals("CURRENT", unchanged.materialStatus());
        assertEquals("采用的读取事实已确认", unchanged.reason());
        var newer = new TaskCollaborationInputService.PrerequisiteResult(9L, 21L, 8L, source.evidence(), List.of());
        assertTrue(TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(newer), List.of(attempt), List.of()).get(0).adoptionRequired());
        var waiting = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("operationId", "wait").put("outcome", "WAIT_FOR_CHANGE");
        waiting.set("review", json.valueToTree(review("WAIT_FOR_CHANGE", List.of(missing))));
        ((com.fasterxml.jackson.databind.node.ArrayNode) attempt.getCheckpoints()).add(waiting);
        assertTrue(TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(source), List.of(attempt), List.of()).get(0).adoptionRequired());
        attempt.setStatus("succeeded"); attempt.setStopConfirmedAt(java.time.LocalDateTime.now());
        attempt.setResultEvidence(json.createObjectNode().put("ok", true)); task.setStatus("done");
        var explanation = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("operationId", "explain").put("outcome", "ADOPT");
        explanation.set("review", json.valueToTree(review("ADOPT", List.of())));
        ((com.fasterxml.jackson.databind.node.ArrayNode) attempt.getCheckpoints()).add(explanation);
        var noFiles = TaskPrerequisiteReviewService.projectStatuses(task, 30L, List.of(source), List.of(attempt), List.of()).get(0);
        assertFalse(noFiles.adoptionRequired()); assertEquals("NOT_APPLICABLE", noFiles.materialStatus());
        assertTrue(TaskPrerequisiteReviewService.projectStatuses(task, 31L, List.of(source), List.of(attempt), List.of()).get(0).adoptionRequired());
    }

    @Test void missingObservationAndUnrelatedOrIncompleteChangesDoNotUnblock() {
        var oldA = new ArtifactObservation("docs/result.md", "PRESENT", "sha256:" + "a".repeat(64));
        var oldB = new ArtifactObservation("docs/second.md", "MISSING", null);
        var value = review("REQUEST_REWORK", List.of(oldA, oldB));
        var fact = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "GOAL_AUTHORIZATION_REQUIRED");
        fact.set("review", json.valueToTree(value)); attempt.setCheckpoints(json.createArrayNode().add(fact));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt));
        var unrelated = new ArtifactObservation("docs/other.md", "PRESENT", "sha256:" + "b".repeat(64));
        var newA = new ArtifactObservation("docs/result.md", "PRESENT", "sha256:" + "b".repeat(64));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of()));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(oldA, oldB, unrelated)))));
        assertNotNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(newA)))));
        assertNull(service.blockingReason("batch", task, List.of(source), List.of(sourceObs(List.of(newA, oldB)))));
        assertNull(service.blockingReason("new-batch", task, List.of(source), List.of()));
        task.setInputRevision(3L);
        assertNull(service.blockingReason("batch", task, List.of(source), List.of()));
    }

    @Test void verifiedNewTraceClearsWaitEvenIfWaitAlreadyObservedItsContent() {
        var old = json.createObjectNode();
        old.putObject("traceOperationIds").put("docs/result.md", "write-v1");
        attempt.setPrerequisiteVersions(json.createArrayNode().add(json.createObjectNode()
                .put("taskId", 9).put("attemptId", 19).put("inputRevision", 7).set("evidence", old)));
        var waiting = json.createObjectNode().put("kind", "PREREQUISITE_REVIEW").put("outcome", "WAIT_FOR_CHANGE");
        waiting.set("review", json.valueToTree(review("WAIT_FOR_CHANGE", List.of(present))));
        attempt.setCheckpoints(json.createArrayNode().add(waiting));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(attempt));
        var fresh = json.createObjectNode();
        fresh.putObject("traceOperationIds").put("docs/result.md", "write-v2");
        fresh.putArray("artifactObservations").add(json.valueToTree(present));
        var updated = new TaskCollaborationInputService.PrerequisiteResult(9L, 19L, 7L, fresh, List.of());
        assertNotNull(service.blockingReason("batch", task, List.of(updated), List.of()));
        assertNull(service.blockingReason("batch", task, List.of(updated), List.of(sourceObs(List.of(present)))));
        fresh.withObject("/traceOperationIds").put("docs/result.md", "write-v1");
        assertNotNull(service.blockingReason("batch", task, List.of(updated), List.of(sourceObs(List.of(present)))));
        var otherPath = new ArtifactObservation("docs/other.md", "PRESENT", "sha256:" + "b".repeat(64));
        fresh.putObject("traceOperationIds").put("docs/other.md", "write-other");
        fresh.putArray("artifactObservations").add(json.valueToTree(otherPath));
        var nextAttempt = new TaskCollaborationInputService.PrerequisiteResult(9L, 21L, 7L, fresh, List.of());
        assertNotNull(service.blockingReason("batch", task, List.of(nextAttempt),
                List.of(new PrerequisiteSourceObservation(21L, 7L, List.of(otherPath)))));
        fresh.putObject("traceOperationIds").put("docs/result.md", "write-v2");
        fresh.putArray("artifactObservations").add(json.valueToTree(present));
        assertNull(service.blockingReason("batch", task, List.of(nextAttempt),
                List.of(new PrerequisiteSourceObservation(21L, 7L, List.of(present)))));
    }

    @Test void adoptionMayUseDeliveryExplanationWithoutReadingUnrelatedArtifacts() {
        var result = service.review(1L, batch, 10L, command, review("ADOPT", List.of()));
        assertTrue(result.path("accepted").asBoolean());
        assertEquals("ADOPT", result.path("outcome").asText());
    }

    @Test void reviewRejectsInvalidProjectPathsAndBadVersion() {
        assertThrows(IllegalArgumentException.class, () -> service.review(1L, batch, 10L, command,
                review("ADOPT", List.of(new ArtifactObservation("../outside", "MISSING", null)))));
        assertThrows(IllegalArgumentException.class, () -> service.review(1L, batch, 10L, command,
                review("ADOPT", List.of(new ArtifactObservation("docs/result.md", "PRESENT", "mtime:1")))));
        assertThrows(IllegalArgumentException.class, () -> service.review(1L, batch, 10L, command,
                review("ADOPT", List.of(missing, missing))));
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
    }

    @Test void reviewTransactionUsesProjectLockChainAndRollsBackOnFailure() throws Exception {
        var tx = TaskPrerequisiteReviewService.class.getMethod("review", Long.class, CollaborationBatchRequest.class,
                Long.class, CollaborationAttemptCommand.class, PrerequisiteReviewRequest.class).getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, tx.isolation());
        assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor());
    }

    private PrerequisiteReviewRequest review(String decision, List<ArtifactObservation> observations) {
        return new PrerequisiteReviewRequest(9L, 19L, 7L, decision, "成果情况", "读取依据", observations, null, null);
    }
    private PrerequisiteSourceObservation sourceObs(List<ArtifactObservation> observations) {
        return new PrerequisiteSourceObservation(19L, 7L, observations);
    }
    private com.fasterxml.jackson.databind.JsonNode savedFact() throws Exception {
        var captured = ArgumentCaptor.forClass(String.class);
        verify(attempts).appendCheckpoint(eq(20L), eq(0), captured.capture());
        return json.readTree(captured.getValue());
    }
}
