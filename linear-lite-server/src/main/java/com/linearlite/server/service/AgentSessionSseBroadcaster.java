package com.linearlite.server.service;

import com.linearlite.server.dto.AgentSessionSnapshot;
import com.linearlite.server.dto.RuntimeDisplayBlockResponse;
import com.linearlite.server.dto.PiSettingsState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * executionId 级透传通道。该类不缓存 SessionSnapshot 或 RuntimeDisplayBlock，
 * 断线后必须重新读取 Pi session，不得回放服务端副本。
 */
@Component
public class AgentSessionSseBroadcaster {
    private final long timeoutMs;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public AgentSessionSseBroadcaster(@Value("${spring.mvc.async.request-timeout}") Duration requestTimeout) {
        this.timeoutMs = requestTimeout.toMillis();
    }

    public SseEmitter register(String executionId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        List<SseEmitter> list = emitters.computeIfAbsent(executionId, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(executionId, list);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }

    public void sendSnapshot(AgentSessionSnapshot snapshot) {
        send(snapshot.executionId(), "session-snapshot", snapshot);
    }

    public void sendRuntime(RuntimeDisplayBlockResponse block) {
        send(block.executionId(), "runtime-display-block", block);
    }

    public void sendReadError(String executionId, String message) {
        send(executionId, "session-read-error", Map.of("executionId", executionId, "message", message));
    }

    public void sendSettingsState(PiSettingsState state) {
        send(state.executionId(), "pi-settings-state", state);
    }

    public void sendSettingsError(String executionId, String message) {
        send(executionId, "pi-settings-error", Map.of("executionId", executionId, "message", message));
    }

    private void send(String executionId, String eventName, Object payload) {
        List<SseEmitter> list = emitters.get(executionId);
        if (list == null) return;
        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException error) {
                list.remove(emitter);
                emitter.complete();
            }
        }
    }

    int subscriberCount(String executionId) {
        List<SseEmitter> list = emitters.get(executionId);
        return list == null ? 0 : list.size();
    }
}
