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

/** 按 execution_id + job_id 暂存并推送 Agent 事件；缓存只存在于当前服务进程，不落库。 */
@Component
public class AgentTaskEventSseBroadcaster {
    private final long timeoutMs;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<String, List<AgentTaskEventResponse>> eventHistory = new ConcurrentHashMap<>();

    public AgentTaskEventSseBroadcaster(
            @Value("${spring.mvc.async.request-timeout}") Duration requestTimeout) {
        this.timeoutMs = requestTimeout.toMillis();
    }

    public SseEmitter register(String executionId, Long jobId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        String channelKey = channelKey(executionId, jobId);
        List<SseEmitter> list = emitters.computeIfAbsent(channelKey, key -> new CopyOnWriteArrayList<>());
        List<AgentTaskEventResponse> history = eventHistory.computeIfAbsent(
                channelKey, key -> new CopyOnWriteArrayList<>());
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(channelKey, list);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        synchronized (history) {
            // 先加入订阅列表，再在同一把锁内回放，确保回放期间新事件不会插队或丢失。
            list.add(emitter);
            for (AgentTaskEventResponse event : List.copyOf(history)) {
                sendToEmitter(emitter, event, list);
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("replay-complete")
                        .data("replayed"));
            } catch (IOException error) {
                remove.run();
                emitter.complete();
            }
        }
        return emitter;
    }

    public void send(String executionId, List<AgentTaskEventResponse> events) {
        if (events.isEmpty()) return;
        for (AgentTaskEventResponse event : events) {
            String channelKey = channelKey(executionId, event.jobId());
            List<AgentTaskEventResponse> history = eventHistory.computeIfAbsent(
                    channelKey, key -> new CopyOnWriteArrayList<>());
            List<SseEmitter> list = emitters.computeIfAbsent(channelKey, key -> new CopyOnWriteArrayList<>());
            synchronized (history) {
                boolean alreadyCached = history.stream().anyMatch(item ->
                        item.jobId().equals(event.jobId()) && item.sequenceNo().equals(event.sequenceNo()));
                if (!alreadyCached) history.add(event);
                for (SseEmitter emitter : List.copyOf(list)) {
                    sendToEmitter(emitter, event, list);
                }
            }
        }
    }

    private String channelKey(String executionId, Long jobId) {
        return executionId + ":" + jobId;
    }

    private void sendToEmitter(SseEmitter emitter, AgentTaskEventResponse event, List<SseEmitter> list) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.jobId() + ":" + event.sequenceNo())
                    .name("agent-event")
                    .data(event));
        } catch (IOException error) {
            list.remove(emitter);
            emitter.complete();
        }
    }
}
