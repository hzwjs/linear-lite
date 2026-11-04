package com.linearlite.server.service;

import com.linearlite.server.config.RemoteTerminalProperties;
import com.linearlite.server.exception.UnprocessableEntityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/** 每个用户滑动一分钟窗口内最多申请 N 次证书，挡住失控的 Agent 循环；进程内计数即可。 */
@Component
public class RemoteTerminalRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final RemoteTerminalProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    @Autowired
    public RemoteTerminalRateLimiter(RemoteTerminalProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RemoteTerminalRateLimiter(RemoteTerminalProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void acquire(Long userId) {
        long now = clock.millis();
        ArrayDeque<Long> window = hits.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= Math.max(1, properties.getCertificateRatePerMinute())) {
                throw new RateLimitedException("证书申请过于频繁，请稍后再试");
            }
            window.addLast(now);
        }
    }

    /** 与 UnprocessableEntityException 并列的 429 语义；由全局异常处理映射。 */
    public static class RateLimitedException extends UnprocessableEntityException {
        public RateLimitedException(String message) {
            super(message);
        }
    }
}
