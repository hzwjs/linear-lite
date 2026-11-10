package com.linearlite.server.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 远程终端团队版的接口载荷。私钥与加密密钥永远不出现在任何载荷中。 */
public final class RemoteTerminalDtos {

    private RemoteTerminalDtos() {
    }

    // ---- 管理端（项目创建者，网页登录态）----

    public record CaView(Long id, String status, String publicKey, String fingerprint, LocalDateTime createdAt) {
    }

    public record ProfileView(Long id, String profileId, String loginUser, Integer maxTtlSeconds,
                              boolean permitPty, String authType, boolean hasPassword, List<Long> grantUserIds) {
    }

    public record TargetView(Long id, String targetId, String alias, String environment, String host, Integer port,
                             String hostKey, String hostKeyFingerprint, Integer hostKeyRev, List<ProfileView> profiles) {
    }

    public record Overview(boolean enabled, String projectIdentifier, List<CaView> cas, List<TargetView> targets) {
    }

    public record TargetRequest(String targetId, String alias, String environment, String host, Integer port,
                                String hostKey) {
    }

    public record ProfileRequest(String profileId, String loginUser, Integer maxTtlSeconds, Boolean permitPty,
                                 String authType, String password) {
        @Override public String toString() { return "ProfileRequest[password=REDACTED]"; }
    }

    public record GrantsRequest(List<Long> userIds) {
    }

    public record CertificateRecord(Long serial, Long userId, String targetId, String profileId, String principal,
                                    String keyFingerprint, LocalDateTime validAfter, LocalDateTime validBefore,
                                    String clientIp, LocalDateTime createdAt) {
    }

    public record AuditRecord(Long id, Long actorId, String action, String subject, String beforeJson,
                              String afterJson, LocalDateTime createdAt) {
    }

    // ---- 客户端（OAuth 令牌，scope=remote-terminal）----

    public record CatalogProfile(String profileId, String loginUser, Integer maxTtlSeconds, String authType) {
    }

    public record CatalogTarget(String targetId, String alias, String environment, String host, Integer port,
                                String hostKey, Integer hostKeyRev, List<CatalogProfile> profiles) {
    }

    public record Catalog(String project, List<CatalogTarget> targets) {
    }

    public record CertificateRequest(String project, String targetId, String profileId, String publicKey,
                                     Integer ttlSeconds) {
    }

    public record PasswordRequest(String project, String targetId, String profileId, Integer ttlSeconds,
                                  Integer expectedHostKeyRev) {
    }

    public record PasswordResponse(String password, boolean permitPty) {
        @Override public String toString() { return "PasswordResponse[password=REDACTED]"; }
    }

    public record CertificateResponse(String certificate, Long serial, String principal, String validBefore) {
    }
}
