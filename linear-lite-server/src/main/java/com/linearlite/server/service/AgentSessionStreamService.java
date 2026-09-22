package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.dto.AgentSessionSnapshot;
import com.linearlite.server.dto.AgentSessionSnapshotClaimResponse;
import com.linearlite.server.dto.RuntimeDisplayBlockBatchRequest;
import com.linearlite.server.dto.RuntimeDisplayBlockRequest;
import com.linearlite.server.dto.RuntimeDisplayBlockResponse;
import com.linearlite.server.dto.SessionDisplayBlock;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Session 历史读取调度与透传的唯一入口；不持久化任何会话内容。 */
@Service
public class AgentSessionStreamService {
    private static final int MAX_BATCH_SIZE = 100;
    private static final Set<String> RUNTIME_KINDS = Set.of("assistant", "tool");
    private static final Set<String> RUNTIME_PHASES = Set.of("streaming", "error");
    private static final Set<String> SESSION_KINDS = Set.of("user", "assistant", "tool");
    private static final Set<String> SESSION_PHASES = Set.of("final", "error");

    private final AgentTaskSessionMapper sessionMapper;
    private final AgentTaskOrchestrationService orchestrationService;
    private final TaskPermissionGuard taskPermissionGuard;
    private final ExecutionCredentialService credentialService;
    private final AgentSessionSseBroadcaster broadcaster;
    private final ConcurrentLinkedQueue<String> queuedRequestIds = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, SnapshotRequest> requests = new ConcurrentHashMap<>();

    public AgentSessionStreamService(
            AgentTaskSessionMapper sessionMapper,
            AgentTaskOrchestrationService orchestrationService,
            TaskPermissionGuard taskPermissionGuard,
            ExecutionCredentialService credentialService,
            AgentSessionSseBroadcaster broadcaster) {
        this.sessionMapper = sessionMapper;
        this.orchestrationService = orchestrationService;
        this.taskPermissionGuard = taskPermissionGuard;
        this.credentialService = credentialService;
        this.broadcaster = broadcaster;
    }

    public SseEmitter stream(String taskKey, Long userId, String executionId) {
        requireTaskSession(taskKey, userId, executionId);
        return broadcaster.register(executionId);
    }

    public synchronized String requestSnapshot(String taskKey, Long userId, String executionId) {
        AgentTaskSession session = requireTaskSession(taskKey, userId, executionId);
        if (!credentialService.isOnline(executionId)) {
            throw new ConflictOperationException("Pi Bridge 离线，无法读取本地 session");
        }
        // 同一 execution 只保留一个待处理读取指令，避免页面重连重复启动 Pi。
        SnapshotRequest existing = requests.values().stream()
                .filter(request -> request.executionId().equals(executionId))
                .findFirst().orElse(null);
        if (existing != null) return existing.requestId();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        SnapshotRequest request = new SnapshotRequest(requestId, executionId, session.getSessionId(),
                session.getProjectId(), userId, Instant.now().plusSeconds(45));
        requests.put(requestId, request);
        queuedRequestIds.add(requestId);
        return requestId;
    }

    public AgentSessionSnapshotClaimResponse claimSnapshot(Long ownerUserId, String executionId) {
        for (String requestId : List.copyOf(queuedRequestIds)) {
            SnapshotRequest request = requests.get(requestId);
            if (request == null) {
                queuedRequestIds.remove(requestId);
                continue;
            }
            if (!request.ownerUserId().equals(ownerUserId) || !request.executionId().equals(executionId)) continue;
            if (!queuedRequestIds.remove(requestId)) continue;
            return new AgentSessionSnapshotClaimResponse(request.requestId(), request.executionId(),
                    request.piSessionId(), request.projectId());
        }
        return null;
    }

    public void completeSnapshot(Long ownerUserId, String requestId, AgentSessionSnapshot snapshot) {
        SnapshotRequest request = requireRequest(ownerUserId, requestId, snapshot.executionId());
        validateSnapshot(snapshot, request);
        requests.remove(requestId);
        queuedRequestIds.remove(requestId);
        broadcaster.sendSnapshot(snapshot);
    }

    public void failSnapshot(Long ownerUserId, String requestId, String executionId, String errorMessage) {
        requireRequest(ownerUserId, requestId, executionId);
        requests.remove(requestId);
        queuedRequestIds.remove(requestId);
        String message = errorMessage == null || errorMessage.isBlank()
                ? "Pi session 读取失败" : errorMessage.trim();
        broadcaster.sendReadError(executionId, message);
    }

    public void publishSessionSnapshot(Long ownerUserId, String executionId, AgentSessionSnapshot snapshot) {
        AgentTaskSession session = requireOwnedSession(ownerUserId, executionId);
        validateSnapshot(snapshot, new SnapshotRequest("agent-end", executionId, session.getSessionId(),
                session.getProjectId(), ownerUserId, Instant.MAX));
        broadcaster.sendSnapshot(snapshot);
    }

    /** 一次性读取指令超时后只发送错误，不返回任何旧快照。 */
    @Scheduled(fixedDelay = 1000)
    public void expireSnapshotRequests() {
        Instant now = Instant.now();
        for (SnapshotRequest request : List.copyOf(requests.values())) {
            if (request.expiresAt().isAfter(now) || !requests.remove(request.requestId(), request)) continue;
            queuedRequestIds.remove(request.requestId());
            broadcaster.sendReadError(request.executionId(), "Pi session 读取超时，请确认 Bridge 在线");
        }
    }

    public void reportRuntime(Long ownerUserId, Long jobId, RuntimeDisplayBlockBatchRequest request) {
        if (request == null || request.getExecutionId() == null || request.getExecutionId().isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        List<RuntimeDisplayBlockRequest> blocks = request.getBlocks();
        if (blocks == null || blocks.isEmpty() || blocks.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("临时显示块批次必须包含 1-100 个快照");
        }
        var job = orchestrationService.requireOwnedJob(ownerUserId, jobId, request.getExecutionId());
        orchestrationService.markJobRunning(job);
        for (RuntimeDisplayBlockRequest block : blocks) {
            validateRuntimeBlock(block);
            broadcaster.sendRuntime(new RuntimeDisplayBlockResponse(
                    request.getExecutionId(), jobId, block.getBlockId(), block.getRevision(), block.getKind(),
                    block.getPhase(), block.getContent(), block.getTool(), LocalDateTime.now()));
        }
    }

    private AgentTaskSession requireTaskSession(String taskKey, Long userId, String executionId) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getTaskId, task.getId())
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, userId));
        if (session == null) throw new ConflictOperationException("执行会话已变化，请重新打开面板");
        return session;
    }

    private AgentTaskSession requireOwnedSession(Long ownerUserId, String executionId) {
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, ownerUserId));
        if (session == null) throw new ConflictOperationException("Pi session 不属于当前 Bridge");
        return session;
    }

    private SnapshotRequest requireRequest(Long ownerUserId, String requestId, String executionId) {
        SnapshotRequest request = requests.get(requestId);
        if (request == null || !request.ownerUserId().equals(ownerUserId)
                || !request.executionId().equals(executionId)) {
            throw new ConflictOperationException("session 快照请求已失效");
        }
        return request;
    }

    private void validateSnapshot(AgentSessionSnapshot snapshot, SnapshotRequest request) {
        if (snapshot == null || !request.executionId().equals(snapshot.executionId())
                || !request.piSessionId().equals(snapshot.piSessionId()) || snapshot.blocks() == null) {
            throw new IllegalArgumentException("SessionSnapshot 与读取请求不匹配");
        }
        long expectedOrder = 1;
        for (SessionDisplayBlock block : snapshot.blocks()) {
            if (block == null || block.blockId() == null || block.blockId().isBlank()
                    || block.entryId() == null || block.entryId().isBlank()
                    || block.order() == null || block.order() != expectedOrder++
                    || !SESSION_KINDS.contains(block.kind()) || !SESSION_PHASES.contains(block.phase())) {
                throw new IllegalArgumentException("SessionDisplayBlock 结构或顺序无效");
            }
            validateContentShape(block.kind(), block.content(), block.tool());
            validateRuntimeIdentity(block);
        }
    }

    /** 历史块与 Runtime 的身份必须由 Pi 原始字段唯一确定，禁止跨层推断。 */
    private void validateRuntimeIdentity(SessionDisplayBlock block) {
        if ("user".equals(block.kind())) {
            if (block.runtimeBlockId() != null) {
                throw new IllegalArgumentException("用户历史块不能关联 Runtime");
            }
            return;
        }
        if (block.runtimeBlockId() == null || block.runtimeBlockId().isBlank()) {
            throw new IllegalArgumentException("Assistant/工具历史块缺少 Runtime 身份");
        }
        if ("assistant".equals(block.kind()) && !block.runtimeBlockId().matches("assistant:\\d+")) {
            throw new IllegalArgumentException("Assistant Runtime 身份无效");
        }
        if ("tool".equals(block.kind()) && !block.blockId().equals(block.runtimeBlockId())) {
            throw new IllegalArgumentException("工具历史块 Runtime 身份无效");
        }
    }

    private void validateRuntimeBlock(RuntimeDisplayBlockRequest block) {
        if (block == null || block.getBlockId() == null || block.getBlockId().isBlank()
                || block.getRevision() == null || block.getRevision() < 1
                || !RUNTIME_KINDS.contains(block.getKind()) || !RUNTIME_PHASES.contains(block.getPhase())) {
            throw new IllegalArgumentException("RuntimeDisplayBlock 结构无效");
        }
        validateContentShape(block.getKind(), block.getContent(), block.getTool());
    }

    private void validateContentShape(String kind, JsonNode content, JsonNode tool) {
        if ("tool".equals(kind)) {
            if (content != null && !content.isNull()) throw new IllegalArgumentException("工具块不能包含 content");
            if (tool == null || !tool.isObject() || !tool.path("name").isTextual()
                    || !tool.path("arguments").isObject() || !tool.path("content").isArray()
                    || !tool.path("isError").isBoolean() || !tool.has("truncation")) {
                throw new IllegalArgumentException("工具块 tool 结构不完整");
            }
            return;
        }
        if (content == null || !content.isObject() || !content.path("text").isTextual()
                || !content.path("thinking").isTextual() || (tool != null && !tool.isNull())) {
            throw new IllegalArgumentException("文本块 content 结构不完整");
        }
    }

    int pendingRequestCount() {
        return requests.size();
    }

    private record SnapshotRequest(
            String requestId,
            String executionId,
            String piSessionId,
            Long projectId,
            Long ownerUserId,
            Instant expiresAt) {
    }
}
