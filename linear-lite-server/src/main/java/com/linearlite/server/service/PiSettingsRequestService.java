package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.PiModelDescriptor;
import com.linearlite.server.dto.PiSettingsRequest;
import com.linearlite.server.dto.PiSettingsRequestClaimResponse;
import com.linearlite.server.dto.PiSettingsState;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pi 设置控制的内存请求通道。模型状态仅由 Pi RPC 回传，不在服务端形成缓存或默认值。
 */
@Service
public class PiSettingsRequestService {
    private static final Set<String> ACTIONS = Set.of("read", "set_model", "set_thinking_level");

    private final AgentTaskSessionMapper sessionMapper;
    private final TaskPermissionGuard taskPermissionGuard;
    private final ExecutionCredentialService credentialService;
    private final AgentSessionSseBroadcaster broadcaster;
    private final ConcurrentLinkedQueue<String> queuedRequestIds = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, SettingsRequest> requests = new ConcurrentHashMap<>();

    public PiSettingsRequestService(
            AgentTaskSessionMapper sessionMapper,
            TaskPermissionGuard taskPermissionGuard,
            ExecutionCredentialService credentialService,
            AgentSessionSseBroadcaster broadcaster) {
        this.sessionMapper = sessionMapper;
        this.taskPermissionGuard = taskPermissionGuard;
        this.credentialService = credentialService;
        this.broadcaster = broadcaster;
    }

    public synchronized String request(String taskKey, Long userId, String executionId, PiSettingsRequest body) {
        AgentTaskSession session = requireTaskSession(taskKey, userId, executionId);
        if (!"waiting_input".equals(session.getStatus())) {
            throw new ConflictOperationException("当前本地 Pi 正在执行，无法切换模型设置");
        }
        if (!credentialService.isOnline(executionId)) {
            throw new ConflictOperationException("Pi Bridge 离线，无法读取本地设置");
        }
        RequestValues values = validateRequest(body);
        // 同一 execution 串行化所有设置控制，避免两个 Pi RPC runtime 同时操作一个 session。
        SettingsRequest existing = requests.values().stream()
                .filter(request -> request.executionId().equals(executionId))
                .findFirst().orElse(null);
        if (existing != null) return existing.requestId();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        SettingsRequest request = new SettingsRequest(requestId, executionId, session.getSessionId(), session.getProjectId(),
                userId, values.action(), values.provider(), values.modelId(), values.level(), Instant.now().plusSeconds(45));
        requests.put(requestId, request);
        queuedRequestIds.add(requestId);
        return requestId;
    }

    public PiSettingsRequestClaimResponse claim(Long ownerUserId, String executionId) {
        for (String requestId : List.copyOf(queuedRequestIds)) {
            SettingsRequest request = requests.get(requestId);
            if (request == null) {
                queuedRequestIds.remove(requestId);
                continue;
            }
            if (!request.ownerUserId().equals(ownerUserId) || !request.executionId().equals(executionId)) continue;
            if (!queuedRequestIds.remove(requestId)) continue;
            return new PiSettingsRequestClaimResponse(request.requestId(), request.executionId(), request.piSessionId(),
                    request.projectId(), request.action(), request.provider(), request.modelId(), request.level());
        }
        return null;
    }

    public void complete(Long ownerUserId, String requestId, PiSettingsState state) {
        SettingsRequest request = requireRequest(ownerUserId, requestId, state == null ? null : state.executionId());
        validateState(state, request.executionId());
        remove(request);
        broadcaster.sendSettingsState(state);
    }

    public void fail(Long ownerUserId, String requestId, String executionId, String errorMessage) {
        SettingsRequest request = requireRequest(ownerUserId, requestId, executionId);
        remove(request);
        String message = errorMessage == null || errorMessage.isBlank() ? "Pi 设置读取或切换失败" : errorMessage.trim();
        broadcaster.sendSettingsError(executionId, message);
    }

    /** 超时不返回旧设置，避免将已过期 Pi runtime 状态作为当前事实。 */
    @Scheduled(fixedDelay = 1000)
    public void expireRequests() {
        Instant now = Instant.now();
        for (SettingsRequest request : List.copyOf(requests.values())) {
            if (request.expiresAt().isAfter(now) || !requests.remove(request.requestId(), request)) continue;
            queuedRequestIds.remove(request.requestId());
            broadcaster.sendSettingsError(request.executionId(), "Pi 设置请求超时，请确认 Bridge 在线");
        }
    }

    int pendingRequestCount() { return requests.size(); }

    private AgentTaskSession requireTaskSession(String taskKey, Long userId, String executionId) {
        Task task = taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
        AgentTaskSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentTaskSession>()
                .eq(AgentTaskSession::getTaskId, task.getId())
                .eq(AgentTaskSession::getExecutionId, executionId)
                .eq(AgentTaskSession::getAgentUserId, userId));
        if (session == null) throw new ConflictOperationException("执行会话已变化，请重新打开面板");
        return session;
    }

    private SettingsRequest requireRequest(Long ownerUserId, String requestId, String executionId) {
        SettingsRequest request = requests.get(requestId);
        if (request == null || executionId == null || !request.ownerUserId().equals(ownerUserId)
                || !request.executionId().equals(executionId)) {
            throw new ConflictOperationException("Pi 设置请求已失效");
        }
        return request;
    }

    private void remove(SettingsRequest request) {
        requests.remove(request.requestId(), request);
        queuedRequestIds.remove(request.requestId());
    }

    private RequestValues validateRequest(PiSettingsRequest request) {
        if (request == null || request.getAction() == null || !ACTIONS.contains(request.getAction())) {
            throw new IllegalArgumentException("Pi 设置操作无效");
        }
        String action = request.getAction();
        String provider = normalize(request.getProvider());
        String modelId = normalize(request.getModelId());
        String level = normalize(request.getLevel());
        if ("read".equals(action) && provider == null && modelId == null && level == null) {
            return new RequestValues(action, null, null, null);
        }
        if ("set_model".equals(action) && provider != null && modelId != null && level == null) {
            return new RequestValues(action, provider, modelId, null);
        }
        if ("set_thinking_level".equals(action) && provider == null && modelId == null && level != null) {
            return new RequestValues(action, null, null, level);
        }
        throw new IllegalArgumentException("Pi 设置参数与操作不匹配");
    }

    private void validateState(PiSettingsState state, String executionId) {
        if (state == null || !executionId.equals(state.executionId()) || state.current() == null
                || !validModel(state.current().model()) || blank(state.current().thinkingLevel())
                || state.models() == null || state.thinkingLevels() == null) {
            throw new IllegalArgumentException("Pi 设置状态结构无效");
        }
        Set<String> modelKeys = new HashSet<>();
        for (PiModelDescriptor model : state.models()) {
            if (!validModel(model) || !modelKeys.add(model.provider() + "\u0000" + model.modelId())) {
                throw new IllegalArgumentException("Pi 模型目录结构无效");
            }
        }
        Set<String> levels = new HashSet<>();
        for (String level : state.thinkingLevels()) {
            if (blank(level) || !levels.add(level)) throw new IllegalArgumentException("Pi 推理强度目录结构无效");
        }
    }

    private boolean validModel(PiModelDescriptor model) {
        return model != null && !blank(model.provider()) && !blank(model.modelId()) && !blank(model.label());
    }

    private String normalize(String value) { return blank(value) ? null : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record RequestValues(String action, String provider, String modelId, String level) { }
    private record SettingsRequest(String requestId, String executionId, String piSessionId, Long projectId,
                                   Long ownerUserId, String action, String provider, String modelId, String level,
                                   Instant expiresAt) { }
}
