package com.linearlite.server.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linearlite.server.config.WeComProperties;
import com.linearlite.server.entity.User;
import com.linearlite.server.event.WeComNotificationRequestedEvent;
import com.linearlite.server.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

@Service
public class WeComNotificationService {

    private static final Logger log = LoggerFactory.getLogger(WeComNotificationService.class);
    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final int MAX_ATTEMPTS = 3;
    private static final long TOKEN_SKEW_SECONDS = 60;

    private final WeComProperties properties;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RestClient restClient;

    private volatile CachedToken cachedToken;

    public WeComNotificationService(
            WeComProperties properties,
            UserMapper userMapper,
            ApplicationEventPublisher applicationEventPublisher) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.restClient = RestClient.builder().baseUrl(API_BASE_URL).build();
    }

    public void requestMentionNotification(Long localUserId, String taskKey, String summary) {
        if (!properties.isEnabled()) {
            return;
        }
        // 事件监听器在事务提交后发送，避免事务回滚后仍然推送企业微信消息。
        applicationEventPublisher.publishEvent(new WeComNotificationRequestedEvent(localUserId, taskKey, summary));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("weComNotificationExecutor")
    public void sendAfterCommit(WeComNotificationRequestedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        User user = userMapper.selectById(event.localUserId());
        if (user == null || user.getWecomUserId() == null || user.getWecomUserId().isBlank()) {
            log.warn("跳过企业微信通知：Linear Lite 用户未配置企业微信 UserID，localUserId={}", event.localUserId());
            return;
        }
        String content = "Linear Lite 通知\n任务：" + event.taskKey() + "\n" + event.summary();
        sendWithRetry(user.getWecomUserId(), content);
    }

    private void sendWithRetry(String wecomUserId, String content) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String accessToken = getAccessToken();
                WeComApiResponse response = restClient.post()
                        .uri(uriBuilder -> uriBuilder.path("/cgi-bin/message/send")
                                .queryParam("access_token", accessToken)
                                .build())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new WeComTextMessage(wecomUserId, properties.getAgentId(), content))
                        .retrieve()
                        .body(WeComApiResponse.class);
                if (response != null && response.errcode() == 0) {
                    return;
                }
                if (response != null && (response.errcode() == 40014 || response.errcode() == 42001)) {
                    cachedToken = null;
                }
                String errmsg = response == null ? "empty response" : response.errmsg();
                log.warn("企业微信通知发送失败，attempt={}, userId={}, errmsg={}", attempt, wecomUserId, errmsg);
            } catch (RestClientException | IllegalStateException ex) {
                log.warn("企业微信通知请求失败，attempt={}, userId={}, message={}", attempt, wecomUserId, ex.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }
    }

    private String getAccessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(TOKEN_SKEW_SECONDS))) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(TOKEN_SKEW_SECONDS))) {
                return current.value();
            }
            if (properties.getCorpId() == null || properties.getCorpId().isBlank()
                    || properties.getSecret() == null || properties.getSecret().isBlank()
                    || properties.getAgentId() == null) {
                throw new IllegalStateException("企业微信通知配置不完整");
            }
            WeComTokenResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/gettoken")
                            .queryParam("corpid", properties.getCorpId())
                            .queryParam("corpsecret", properties.getSecret())
                            .build())
                    .retrieve()
                    .body(WeComTokenResponse.class);
            if (response == null || response.errcode() != 0 || response.accessToken() == null
                    || response.accessToken().isBlank() || response.expiresIn() == null) {
                String errmsg = response == null ? "empty response" : response.errmsg();
                throw new IllegalStateException("企业微信 access_token 获取失败: " + errmsg);
            }
            cachedToken = new CachedToken(
                    response.accessToken(),
                    Instant.now().plusSeconds(response.expiresIn()));
            return response.accessToken();
        }
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(200L * (1L << (attempt - 1)));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    private record WeComApiResponse(int errcode, String errmsg) {
    }

    private record WeComTokenResponse(
            int errcode,
            String errmsg,
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }

    private record WeComTextMessage(
            @JsonProperty("touser") String toUser,
            @JsonProperty("agentid") Integer agentId,
            @JsonProperty("msgtype") String messageType,
            TextContent text,
            int safe) {
        private WeComTextMessage(String toUser, Integer agentId, String content) {
            this(toUser, agentId, "text", new TextContent(content), 0);
        }
    }

    private record TextContent(String content) {
    }

}
