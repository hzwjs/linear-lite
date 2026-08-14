package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.AgentTaskEventBatchRequest;
import com.linearlite.server.dto.AgentTaskEventRequest;
import com.linearlite.server.dto.AgentTaskEventResponse;
import com.linearlite.server.entity.AgentTaskJob;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

/** Agent 事件的唯一写入口：校验所有权与顺序后仅推送实时事件，不建立历史记录。 */
@Service
public class AgentTaskEventService {
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_SUMMARY_LENGTH = 512;
    private static final int MAX_PAYLOAD_LENGTH = 8192;
    private static final Set<String> EVENT_TYPES = Set.of(
            "started", "progress", "tool_call", "tool_result", "completed", "failed");

    private final AgentTaskSessionMapper sessionMapper;
    private final AgentTaskOrchestrationService orchestrationService;
    private final TaskPermissionGuard taskPermissionGuard;
    private final AgentTaskEventSseBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public AgentTaskEventService(
            AgentTaskSessionMapper sessionMapper,
            AgentTaskOrchestrationService orchestrationService,
            TaskPermissionGuard taskPermissionGuard,
            AgentTaskEventSseBroadcaster broadcaster,
            ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.orchestrationService = orchestrationService;
        this.taskPermissionGuard = taskPermissionGuard;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void report(Long agentUserId, Long jobId, AgentTaskEventBatchRequest request) {
        if (request == null || request.getExecutionId() == null || request.getExecutionId().isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        List<AgentTaskEventRequest> requests = request.getEvents();
        if (requests == null || requests.isEmpty() || requests.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("事件批次必须包含 1-100 条事件");
        }

        List<AgentTaskEventResponse> realtimeEvents = new ArrayList<>(requests.size());
        Long previousSequence = null;
        for (AgentTaskEventRequest item : requests) {
            validateIdentity(item);
            if (previousSequence != null && item.getSequenceNo() <= previousSequence) {
                throw new ConflictOperationException("事件 sequence_no 必须在批次内严格递增");
            }
            String payload = toPayload(item);
            realtimeEvents.add(new AgentTaskEventResponse(
                    null,
                    request.getExecutionId(),
                    jobId,
                    item.getSequenceNo(),
                    item.getEventType(),
                    item.getSummary().trim(),
                    payload,
                    LocalDateTime.now()));
            previousSequence = item.getSequenceNo();
        }
        AgentTaskJob job = orchestrationService.requireOwnedJob(agentUserId, jobId, request.getExecutionId());
        orchestrationService.markJobRunning(job);
        if (!realtimeEvents.isEmpty()) {
            List<AgentTaskEventResponse> immutableEvents = List.copyOf(realtimeEvents);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcaster.send(request.getExecutionId(), immutableEvents);
                    }
                });
            } else {
                broadcaster.send(request.getExecutionId(), immutableEvents);
            }
        }
    }

    public void requireTaskExecution(String taskKey, Long userId, String executionId) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        requireTaskExecution(task.getId(), executionId);
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            String taskKey, Long userId, String executionId, Long jobId) {
        requireTaskExecution(taskKey, userId, executionId);
        var status = orchestrationService.getTaskStatus(taskKey, userId);
        if (!executionId.equals(status.executionId()) || !jobId.equals(status.jobId())) {
            throw new ConflictOperationException("Agent Job 已不是任务的当前执行");
        }
        return broadcaster.register(executionId, jobId);
    }

    private void requireTaskExecution(Long taskId, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getTaskId, taskId)
                .eq(AgentTaskSession::getExecutionId, executionId));
        if (session == null) throw new ResourceNotFoundException("执行会话不存在");
    }

    private void validateIdentity(AgentTaskEventRequest item) {
        if (item == null || item.getSequenceNo() == null || item.getSequenceNo() < 1) {
            throw new IllegalArgumentException("事件 sequence_no 必须为正整数");
        }
        if (!EVENT_TYPES.contains(item.getEventType())) {
            throw new IllegalArgumentException("不支持的 Agent 事件类型");
        }
        if (item.getSummary() == null || item.getSummary().isBlank()
                || item.getSummary().trim().length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("事件 summary 不能为空且不能超过 512 个字符");
        }
        if (item.getPayload() == null) throw new IllegalArgumentException("事件 payload 不能为空");
    }

    private String toPayload(AgentTaskEventRequest item) {
        try {
            String payload = objectMapper.writeValueAsString(item.getPayload());
            if (payload.length() > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException("事件 payload 不能超过 8192 个字符");
            return payload;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件 payload 无法序列化", e);
        }
    }

}
