package com.linearlite.server.service;

import com.linearlite.server.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机执行绑定只存在于服务端内存，不形成可配置、可复制的产品凭证。
 * 浏览器关闭或绑定过期后，Bridge 需要重新从当前任务面板 attach。
 */
@Service
public class BridgeExecutionAttachmentService {
    private static final long PENDING_TTL_SECONDS = 120;
    private static final long ACTIVE_TTL_SECONDS = 1800;

    private final ConcurrentHashMap<String, PendingAttachment> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveAttachment> active = new ConcurrentHashMap<>();

    public String issue(Long ownerUserId, String executionId) {
        String code = UUID.randomUUID().toString().replace("-", "");
        pending.put(code, new PendingAttachment(ownerUserId, executionId,
                Instant.now().plusSeconds(PENDING_TTL_SECONDS)));
        return code;
    }

    public Attachment attach(String code) {
        PendingAttachment invitation = pending.remove(code);
        if (invitation == null || invitation.expiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("本地 Pi 执行绑定已过期，请重新打开执行面板");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        active.put(token, new ActiveAttachment(invitation.ownerUserId(), invitation.executionId(),
                now, now.plusSeconds(ACTIVE_TTL_SECONDS)));
        return new Attachment(token, invitation.executionId());
    }

    public Long authenticate(String token, String executionId) {
        ActiveAttachment attachment = active.get(token);
        if (attachment == null || attachment.expiresAt().isBefore(Instant.now())
                || !attachment.executionId().equals(executionId)) {
            if (attachment != null) active.remove(token);
            throw new UnauthorizedException("本地 Pi 执行绑定无效或已过期");
        }
        Instant now = Instant.now();
        active.put(token, new ActiveAttachment(attachment.ownerUserId(), attachment.executionId(),
                now, now.plusSeconds(ACTIVE_TTL_SECONDS)));
        return attachment.ownerUserId();
    }

    public Long authenticate(String token) {
        ActiveAttachment attachment = active.get(token);
        if (attachment == null || attachment.expiresAt().isBefore(Instant.now())) {
            if (attachment != null) active.remove(token);
            throw new UnauthorizedException("本地 Pi 执行绑定无效或已过期");
        }
        Instant now = Instant.now();
        active.put(token, new ActiveAttachment(attachment.ownerUserId(), attachment.executionId(),
                now, now.plusSeconds(ACTIVE_TTL_SECONDS)));
        return attachment.ownerUserId();
    }

    public String executionId(String token) {
        ActiveAttachment attachment = requireActive(token);
        return attachment.executionId();
    }

    /** 快照请求不得在 Bridge 离线时返回旧内容；只认最近真实认证过的本机连接。 */
    public boolean isOnline(String executionId) {
        Instant threshold = Instant.now().minusSeconds(10);
        return active.values().stream().anyMatch(attachment ->
                attachment.executionId().equals(executionId)
                        && attachment.expiresAt().isAfter(Instant.now())
                        && attachment.lastSeenAt().isAfter(threshold));
    }

    private ActiveAttachment requireActive(String token) {
        ActiveAttachment attachment = active.get(token);
        if (attachment == null || attachment.expiresAt().isBefore(Instant.now())) {
            if (attachment != null) active.remove(token);
            throw new UnauthorizedException("本地 Pi 执行绑定无效或已过期");
        }
        return attachment;
    }

    public record Attachment(String token, String executionId) {}
    private record PendingAttachment(Long ownerUserId, String executionId, Instant expiresAt) {}
    private record ActiveAttachment(Long ownerUserId, String executionId, Instant lastSeenAt, Instant expiresAt) {}
}
