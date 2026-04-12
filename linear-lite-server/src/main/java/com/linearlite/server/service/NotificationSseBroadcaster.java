package com.linearlite.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 按用户 ID 维护 SSE 连接，用于站内通知推送。
 */
@Component
public class NotificationSseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseBroadcaster.class);

    private final long sseTimeoutMs;
    private final Map<Long, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public NotificationSseBroadcaster(
            @Value("${spring.mvc.async.request-timeout}") Duration springAsyncRequestTimeout) {
        this.sseTimeoutMs = springAsyncRequestTimeout.toMillis();
    }

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        List<SseEmitter> list = userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                userEmitters.remove(userId, list);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /**
     * @param eventName 客户端 EventSource 监听的 event 名，如 {@code notification}
     */
    public void sendToUser(Long userId, String eventName, Object data) {
        List<SseEmitter> list = userEmitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                log.debug("SSE send failed, removing emitter: {}", e.getMessage());
                list.remove(emitter);
                emitter.complete();
            }
        }
    }
}
