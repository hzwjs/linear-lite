package com.linearlite.server.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.entity.TaskExecutionAttempt;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

/** 领取标识摘要不进入执行记录响应。 */
public record TaskExecutionAttemptResponse(
        Long id, Long taskId, Long bindingId, String batchId, String hostInstanceId, String agentInstanceId,
        Long inputRevision, JsonNode prerequisiteVersions, String status,
        Instant leaseUntil, JsonNode checkpoints, JsonNode resultEvidence, String stopReason,
        LocalDateTime stopRequestedAt, LocalDateTime stopConfirmedAt, JsonNode stopConfirmation,
        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime finishedAt) {
    public static TaskExecutionAttemptResponse from(TaskExecutionAttempt attempt) {
        return new TaskExecutionAttemptResponse(attempt.getId(), attempt.getTaskId(), attempt.getBindingId(),
                attempt.getBatchId(), attempt.getHostInstanceId(), attempt.getAgentInstanceId(),
                attempt.getInputRevision(), attempt.getPrerequisiteVersions(),
                attempt.getStatus(), CollaborationLeaseTime.toInstant(attempt.getLeaseUntil()), attempt.getCheckpoints(), attempt.getResultEvidence(),
                attempt.getStopReason(), attempt.getStopRequestedAt(), attempt.getStopConfirmedAt(),
                attempt.getStopConfirmation(), attempt.getCreatedAt(), attempt.getUpdatedAt(), attempt.getFinishedAt());
    }
}
