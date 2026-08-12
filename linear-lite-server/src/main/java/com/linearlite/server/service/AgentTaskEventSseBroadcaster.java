package com.linearlite.server.service;

import com.linearlite.server.dto.AgentTaskEventResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 按 execution_id 推送 Agent 事件；事件只存在于当前 SSE 生命周期，不提供历史回放。 */
@Component
public class AgentTaskEventSseBroadcaster {
    private final long timeoutMs;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public AgentTaskEventSseBroadcaster(
            @Value("${spring.mvc.async.request-timeout}") Duration requestTimeout) {
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

    public void send(String executionId, List<AgentTaskEventResponse> events) {
        List<SseEmitter> list = emitters.get(executionId);
        if (list == null || list.isEmpty()) return;
        for (AgentTaskEventResponse event : events) {
            for (SseEmitter emitter : List.copyOf(list)) {
                try {
                    emitter.send(SseEmitter.event()
                            // 没有数据库主键时使用 Job 与序号组成当前流内稳定标识。
                            .id(event.jobId() + ":" + event.sequenceNo())
                            .name("agent-event")
                            .data(event));
                } catch (IOException error) {
                    list.remove(emitter);
                    emitter.complete();
                }
            }
        }
    }
}
