package com.linearlite.server.dto;

import com.linearlite.server.service.TaskCollaborationInputService.PrerequisiteResult;
import com.linearlite.server.service.CollaborationContextService.ContextStatus;
import com.linearlite.server.service.TaskCollaborationDependencyService.DependencyFact;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 任务状态与协作事实由同次读取投影。 */
public record TaskCollaborationState(Instant serverTime, Long inputRevision, String status, Integer progressPercent,
        LocalDateTime completedAt, LocalDateTime updatedAt,
        boolean activeOrFrozen, List<TaskExecutionAttemptResponse> attempts,
        List<BindingIdentity> attemptBindings, List<BindingEvaluation> bindings, List<AttemptContext> contextFacts,
        List<AttemptPrerequisiteReviews> prerequisiteReviewFacts, List<DependencyFact> dependencyFacts,
        List<GoalAuthorizationRequest> goalAuthorizationRequests) {
    public record Request(String taskKey) { }
    public record BindingIdentity(Long id, String deviceId) { }
    public record AttemptContext(Long attemptId, List<ContextStatus> refs) { }
    public record BindingEvaluation(Long bindingId, String deviceId, boolean inputsReady, String inputsWaitingReason,
            List<PrerequisiteResult> prerequisiteResults, List<PrerequisiteReviewStatus> prerequisiteReviewStatuses) { }
}
