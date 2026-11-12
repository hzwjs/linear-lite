package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.GoalAuthorizationRequest;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.service.TaskCollaborationDependencyService.DependencyFact;
import com.linearlite.server.service.TaskPrerequisiteReviewService.ReviewFact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 把有效尝试留下的新目标事实投影给批次拥有者。 */
@Service
public class CollaborationGoalRequests {
    public static final String RECEIPT_KIND = "goal_authorize";
    private final CollaborationBatchMapper batches;
    private final TaskPrerequisiteReviewService reviews;
    private final TaskExecutionOperationService operations;
    private final ObjectMapper json;

    public CollaborationGoalRequests(CollaborationBatchMapper batches, TaskPrerequisiteReviewService reviews,
                                     TaskExecutionOperationService operations, ObjectMapper json) {
        this.batches = batches;
        this.reviews = reviews;
        this.operations = operations;
        this.json = json;
    }

    public record Candidate(GoalAuthorizationRequest view, Long attemptId, Long inputRevision,
                            Long sourceTaskId, Long sourceAttemptId, Long sourceInputRevision) { }

    public List<GoalAuthorizationRequest> project(Task task, Long userId, String batchId,
                                                   List<TaskExecutionAttempt> history, List<DependencyFact> dependencyFacts) {
        return candidates(task, userId, batchId, history, dependencyFacts).stream().map(Candidate::view).toList();
    }

    public List<Candidate> candidates(Task task, Long userId, String batchId,
                                      List<TaskExecutionAttempt> history, List<DependencyFact> dependencyFacts) {
        Map<String, CollaborationBatch> seenBatches = new HashMap<>();
        Map<String, List<Long>> batchTasks = new HashMap<>();
        Map<Long, TaskExecutionAttempt> ownedAttempts = new HashMap<>();
        for (TaskExecutionAttempt attempt : history) {
            if (!Objects.equals(attempt.getTaskId(), task.getId()) || attempt.getBatchId() == null
                    || batchId != null && !Objects.equals(batchId, attempt.getBatchId())) continue;
            CollaborationBatch batch = seenBatches.computeIfAbsent(attempt.getBatchId(), batches::selectById);
            if (batch == null || !Objects.equals(batch.getUserId(), userId)
                    || !Objects.equals(batch.getProjectId(), task.getProjectId())
                    || !Objects.equals(batch.getBatchId(), attempt.getBatchId())
                    || !Objects.equals(batch.getBindingId(), attempt.getBindingId())) continue;
            List<Long> taskIds = batchTasks.computeIfAbsent(batch.getBatchId(), batches::selectTaskIds);
            if (!taskIds.contains(task.getId()) || attempt.getStopRequestedAt() == null || attempt.getStopConfirmedAt() == null
                    || !("stopped".equals(attempt.getStatus()) || "failed".equals(attempt.getStatus()))) continue;
            ownedAttempts.put(attempt.getId(), attempt);
        }
        List<Candidate> result = new ArrayList<>();
        for (DependencyFact fact : dependencyFacts) {
            TaskExecutionAttempt attempt = ownedAttempts.get(fact.attemptId());
            if (attempt == null || !fact.accepted() || !"GOAL_AUTHORIZATION_REQUIRED".equals(fact.outcome())
                    || !"REQUEST_GOAL".equals(fact.change().action()) || !Objects.equals(fact.actorUserId(), userId)
                    || !Objects.equals(fact.batchId(), attempt.getBatchId())
                    || !Objects.equals(fact.sourceInputRevision(), attempt.getInputRevision())) continue;
            add(task, userId, attempt, fact.operationId(), fact.change().goal(), fact.change().impact(),
                    null, null, null, result);
        }
        for (TaskExecutionAttempt attempt : history) {
            if (!ownedAttempts.containsKey(attempt.getId())) continue;
            for (ReviewFact fact : reviews.project(attempt)) {
                var review = fact.review();
                if (!"GOAL_AUTHORIZATION_REQUIRED".equals(fact.outcome()) || !"REQUEST_REWORK".equals(review.decision())
                        || review.sourceTaskId() == null || review.sourceTaskId() <= 0
                        || review.sourceAttemptId() == null || review.sourceAttemptId() <= 0
                        || review.sourceInputRevision() == null || review.sourceInputRevision() <= 0
                        || batchTasks.get(attempt.getBatchId()).contains(review.sourceTaskId())) continue;
                add(task, userId, attempt, fact.operationId(), review.goal(), review.impact(),
                        review.sourceTaskId(), review.sourceAttemptId(), review.sourceInputRevision(), result);
            }
        }
        return List.copyOf(result);
    }

    private void add(Task task, Long userId, TaskExecutionAttempt attempt, String requestOperationId, String goal,
                     String impact, Long sourceTaskId, Long sourceAttemptId, Long sourceInputRevision,
                     List<Candidate> result) {
        if (requestOperationId == null || !requestOperationId.matches("^[A-Za-z0-9_-]{1,128}$")
                || goal == null || goal.isBlank() || impact == null || impact.isBlank()) return;
        JsonNode receipt = operations.findCommittedReplay(task.getProjectId(), userId, receiptId(requestOperationId),
                RECEIPT_KIND, request(json, task.getProjectId(), attempt.getBatchId(), requestOperationId));
        String authorizedBatchId = null;
        if (receipt != null) {
            JsonNode value = receipt.get("batchId");
            if (value == null || !value.isTextual() || value.textValue().isBlank())
                throw new IllegalStateException("目标授权回执无效");
            authorizedBatchId = value.textValue();
        } else if (!"todo".equalsIgnoreCase(task.getStatus())
                || !Objects.equals(task.getInputRevision(), attempt.getInputRevision())) return;
        GoalAuthorizationRequest view = new GoalAuthorizationRequest(attempt.getBatchId(), requestOperationId,
                goal, impact, authorizedBatchId);
        result.add(new Candidate(view, attempt.getId(), attempt.getInputRevision(),
                sourceTaskId, sourceAttemptId, sourceInputRevision));
    }

    public static ObjectNode request(ObjectMapper json, Long projectId, String batchId, String requestOperationId) {
        return json.createObjectNode().put("projectId", projectId).put("batchId", batchId)
                .put("requestOperationId", requestOperationId);
    }

    public static String receiptId(String requestOperationId) {
        if (requestOperationId == null || !requestOperationId.matches("^[A-Za-z0-9_-]{1,128}$"))
            throw new IllegalArgumentException("目标授权请求标识无效");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requestOperationId.getBytes(StandardCharsets.UTF_8));
            return "goal-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
