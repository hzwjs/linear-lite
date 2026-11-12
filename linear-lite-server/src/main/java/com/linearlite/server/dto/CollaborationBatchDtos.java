package com.linearlite.server.dto;

import com.linearlite.server.service.TaskCollaborationInputService.PrerequisiteResult;
import com.linearlite.server.service.TaskCollaborationDependencyService.DependencyFact;
import com.linearlite.server.dto.TaskCollaborationState.AttemptContext;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** 批次控制数据；任务状态和执行记录仍从原始数据读取。 */
public final class CollaborationBatchDtos {
    private CollaborationBatchDtos() { }

    public record StartRequest(Long projectId, String operationId, Long bindingId, Long bindingRevision,
                               List<Long> taskIds, Long maxDurationMs) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("启动请求包含未知字段");
        }
    }
    /** 单任务直跑：设备与时长由服务端补全，设备不唯一时由用户指定 bindingId。 */
    public record StartSingleRequest(Long projectId, String operationId, Long taskId, Long bindingId) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("启动请求包含未知字段");
        }
    }
    public record StopRequest(Long projectId, String operationId, String batchId) { }
    public record AuthorizeGoalRequest(Long projectId, String batchId, String requestOperationId) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("目标授权请求包含未知字段");
        }
    }
    public record ListRequest(Long projectId, Integer limit, String beforeBatchId) { }
    public record GetRequest(Long projectId, String batchId) { }
    public record CommandResult(String batchId, String lifecycle, String desiredIntent, Long intentRevision,
                                String displayState) { }
    public record Summary(String batchId, Long projectId, Long userId, Long bindingId, Long bindingRevision,
                          Long hostAuthorizationId, Integer maxSlots, Long maxDurationMs, Integer maxAttempts,
                          String desiredIntent, Long intentRevision, String lifecycle, String hostInstanceId,
                          Long runEpoch, Instant runLeaseUntil, Instant startedAt, Instant deadlineAt, Instant endedAt,
                          String endReason, Instant createdAt, long attemptsUsed, String displayState) { }
    public record TaskView(Long taskId, String taskKey, String title, String status,
                           Long inputRevision,
                           boolean inputsReady, String inputsWaitingReason, boolean activeOrFrozen,
                           List<PrerequisiteResult> prerequisiteResults,
                           List<TaskExecutionAttemptResponse> attempts, List<AttemptPrerequisiteReviews> prerequisiteReviewFacts,
                           List<PrerequisiteReviewStatus> prerequisiteReviewStatuses,
                           List<AttemptContext> contextFacts, List<DependencyFact> dependencyFacts,
                           List<GoalAuthorizationRequest> goalAuthorizationRequests) { }
    public record Detail(Instant serverTime, Summary batch, List<TaskView> tasks) { }
    public record Page(List<Summary> items, boolean hasMore, String nextCursor) { }
}
