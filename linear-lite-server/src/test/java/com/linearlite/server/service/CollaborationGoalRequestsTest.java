package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.DependencyChange;
import com.linearlite.server.dto.PrerequisiteReviewRequest;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.service.TaskCollaborationDependencyService.DependencyFact;
import com.linearlite.server.service.TaskPrerequisiteReviewService.ReviewFact;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollaborationGoalRequestsTest {
    private final CollaborationBatchMapper batches = mock(CollaborationBatchMapper.class);
    private final TaskPrerequisiteReviewService reviews = mock(TaskPrerequisiteReviewService.class);
    private final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CollaborationGoalRequests service = new CollaborationGoalRequests(batches, reviews, operations, json);
    private Task task;
    private TaskExecutionAttempt attempt;
    private CollaborationBatch batch;
    private DependencyFact goal;

    @BeforeEach void setup() {
        task = new Task(); task.setId(10L); task.setProjectId(6L); task.setStatus("todo"); task.setInputRevision(2L);
        batch = new CollaborationBatch(); batch.setBatchId("batch-a"); batch.setUserId(1L);
        batch.setProjectId(6L); batch.setBindingId(30L);
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L); attempt.setBindingId(30L);
        attempt.setInputRevision(2L); attempt.setBatchId("batch-a"); attempt.setStatus("stopped");
        attempt.setStopRequestedAt(LocalDateTime.now()); attempt.setStopConfirmedAt(LocalDateTime.now());
        when(batches.selectById("batch-a")).thenReturn(batch);
        when(batches.selectTaskIds("batch-a")).thenReturn(List.of(10L));
        var change = new DependencyChange("REQUEST_GOAL", null, "缺项", "发现新需求", "需求说明", "新增物流", "涉及新服务");
        goal = new DependencyFact("request-1", 20L, 1L, "batch-a", 2L, LocalDateTime.now(), change,
                true, false, "GOAL_AUTHORIZATION_REQUIRED", null);
    }

    @Test void ownStoppedGoalIsProjectedAndKeepsRequestProofOutsideVisibleBusinessText() {
        var candidates = service.candidates(task, 1L, "batch-a", List.of(attempt), List.of(goal));
        assertEquals(1, candidates.size());
        var candidate = candidates.get(0);
        assertEquals("新增物流", candidate.view().goal());
        assertEquals("涉及新服务", candidate.view().impact());
        assertNull(candidate.view().authorizedBatchId());
        assertEquals(20L, candidate.attemptId());
        assertEquals(2L, candidate.inputRevision());
        assertNull(candidate.sourceTaskId());
        verify(operations).findCommittedReplay(eq(6L), eq(1L), eq(CollaborationGoalRequests.receiptId("request-1")),
                eq("goal_authorize"), eq(CollaborationGoalRequests.request(json, 6L, "batch-a", "request-1")));
    }

    @Test void onlyOwnedBatchMemberAndConfirmedCurrentTodoAttemptCanRequestAuthorization() {
        assertTrue(service.project(task, 2L, null, List.of(attempt), List.of(goal)).isEmpty());
        assertTrue(service.project(task, 1L, "other-batch", List.of(attempt), List.of(goal)).isEmpty());
        attempt.setStopConfirmedAt(null);
        assertTrue(service.project(task, 1L, "batch-a", List.of(attempt), List.of(goal)).isEmpty());
        attempt.setStopConfirmedAt(LocalDateTime.now());
        task.setInputRevision(3L);
        assertTrue(service.project(task, 1L, "batch-a", List.of(attempt), List.of(goal)).isEmpty());
        task.setInputRevision(2L); task.setStatus("done");
        assertTrue(service.project(task, 1L, "batch-a", List.of(attempt), List.of(goal)).isEmpty());
        task.setStatus("todo");
        when(batches.selectTaskIds("batch-a")).thenReturn(List.of(11L));
        assertTrue(service.project(task, 1L, "batch-a", List.of(attempt), List.of(goal)).isEmpty());
    }

    @Test void missingOutsideBatchAndUnacceptedFactsDoNotRequestAuthorization() {
        var add = new DependencyChange("ADD", 11L, "成果缺失", "等待", "证据", "目标", "影响");
        var missing = new DependencyFact("missing", 20L, 1L, "batch-a", 2L, LocalDateTime.now(), add,
                true, false, "MISSING_OUTSIDE_BATCH", null);
        var rejected = new DependencyFact("rejected", 20L, 1L, "batch-a", 2L, LocalDateTime.now(), goal.change(),
                false, false, "GOAL_AUTHORIZATION_REQUIRED", null);
        assertTrue(service.project(task, 1L, null, List.of(attempt), List.of(missing, rejected)).isEmpty());
        verifyNoInteractions(operations);
    }

    @Test void outsideReviewKeepsSourceIdentityAndSameBatchReviewIsExcluded() {
        var review = new PrerequisiteReviewRequest(15L, 50L, 3L, "REQUEST_REWORK", "缺成果", "读取缺失", List.of(), "新增目标", "业务影响");
        var fact = new ReviewFact("review-1", "agent", "2026-11-11T10:00:00", "GOAL_AUTHORIZATION_REQUIRED", review);
        when(reviews.project(attempt)).thenReturn(List.of(fact));
        var candidate = service.candidates(task, 1L, "batch-a", List.of(attempt), List.of()).get(0);
        assertEquals(15L, candidate.sourceTaskId());
        assertEquals(50L, candidate.sourceAttemptId());
        assertEquals(3L, candidate.sourceInputRevision());
        when(batches.selectTaskIds("batch-a")).thenReturn(List.of(10L, 15L));
        assertTrue(service.project(task, 1L, "batch-a", List.of(attempt), List.of()).isEmpty());
    }

    @Test void committedAuthorizationIsVisibleAfterTaskRevisionChanges() {
        var receipt = json.createObjectNode().put("batchId", "batch-new");
        when(operations.findCommittedReplay(eq(6L), eq(1L), eq(CollaborationGoalRequests.receiptId("request-1")),
                eq("goal_authorize"), any())).thenReturn(receipt);
        task.setInputRevision(3L); task.setStatus("done");
        var view = service.project(task, 1L, "batch-a", List.of(attempt), List.of(goal)).get(0);
        assertEquals("batch-new", view.authorizedBatchId());
        assertEquals("request-1", view.requestOperationId());
    }

    @Test void receiptKeyAndRequestAreStableAndDoNotContainCredentials() {
        assertEquals(CollaborationGoalRequests.receiptId("request-1"), CollaborationGoalRequests.receiptId("request-1"));
        assertNotEquals(CollaborationGoalRequests.receiptId("request-1"), CollaborationGoalRequests.receiptId("request-2"));
        assertEquals(69, CollaborationGoalRequests.receiptId("request-1").length());
        assertThrows(IllegalArgumentException.class, () -> CollaborationGoalRequests.receiptId("bad/id"));
        assertEquals("{\"projectId\":6,\"batchId\":\"batch-a\",\"requestOperationId\":\"request-1\"}",
                CollaborationGoalRequests.request(json, 6L, "batch-a", "request-1").toString());
    }
}
