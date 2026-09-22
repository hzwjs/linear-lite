package com.linearlite.server.service;

import com.linearlite.server.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端签发并校验单个执行上下文的短期凭据。Bridge 只携带凭据，不参与用户或项目权限判断。
 */
@Service
public class ExecutionCredentialService {
    private static final long ACTIVE_TTL_SECONDS = 1800;

    private final ConcurrentHashMap<String, Credential> credentials = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenByExecution = new ConcurrentHashMap<>();

    public String issue(Long ownerUserId, String executionId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String previousToken = tokenByExecution.put(executionId, token);
        if (previousToken != null) {
            credentials.remove(previousToken);
        }
        credentials.put(token, new Credential(ownerUserId, executionId, now, now.plusSeconds(ACTIVE_TTL_SECONDS)));
        return token;
    }

    public Long authenticate(String token, String executionId) {
        Credential credential = requireCredential(token);
        if (!credential.executionId().equals(executionId)) {
            throw new UnauthorizedException("本地 Pi 执行凭据与执行上下文不匹配");
        }
        refresh(token, credential);
        return credential.ownerUserId();
    }

    public Long authenticate(String token) {
        Credential credential = requireCredential(token);
        refresh(token, credential);
        return credential.ownerUserId();
    }

    public String executionId(String token) {
        return requireCredential(token).executionId();
    }

    public boolean isOnline(String executionId) {
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(10);
        return credentials.values().stream().anyMatch(credential ->
                credential.executionId().equals(executionId)
                        && credential.expiresAt().isAfter(now)
                        && credential.lastSeenAt().isAfter(threshold));
    }

    private Credential requireCredential(String token) {
        Credential credential = credentials.get(token);
        if (credential == null || credential.expiresAt().isBefore(Instant.now())) {
            if (credential != null) {
                credentials.remove(token);
                tokenByExecution.remove(credential.executionId(), token);
            }
            throw new UnauthorizedException("本地 Pi 执行凭据无效或已过期");
        }
        return credential;
    }

    private void refresh(String token, Credential credential) {
        Instant now = Instant.now();
        credentials.put(token, new Credential(credential.ownerUserId(), credential.executionId(),
                now, now.plusSeconds(ACTIVE_TTL_SECONDS)));
    }

    private record Credential(Long ownerUserId, String executionId, Instant lastSeenAt, Instant expiresAt) {}
}
