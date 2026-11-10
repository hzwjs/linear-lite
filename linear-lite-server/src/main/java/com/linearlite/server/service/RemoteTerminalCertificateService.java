package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.RemoteTerminalDtos.Catalog;
import com.linearlite.server.dto.RemoteTerminalDtos.CatalogProfile;
import com.linearlite.server.dto.RemoteTerminalDtos.CatalogTarget;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateResponse;
import com.linearlite.server.dto.RemoteTerminalDtos.PasswordRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.PasswordResponse;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.RtCaKey;
import com.linearlite.server.entity.RtCertificate;
import com.linearlite.server.entity.RtGrant;
import com.linearlite.server.entity.RtProfile;
import com.linearlite.server.entity.RtTarget;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.exception.UnprocessableEntityException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.RtCaKeyMapper;
import com.linearlite.server.mapper.RtCertificateMapper;
import com.linearlite.server.mapper.RtGrantMapper;
import com.linearlite.server.mapper.RtProfileMapper;
import com.linearlite.server.mapper.RtTargetMapper;
import com.linearlite.server.sshca.CaKeyCipher;
import com.linearlite.server.sshca.SshCertificateSigner;
import com.linearlite.server.sshca.SshKeys;
import com.linearlite.server.time.BeijingTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 成员侧：读取自己被授权的目录，并为某个授权身份申请短期 SSH 证书。 */
@Service
public class RemoteTerminalCertificateService {

    static final int MAX_TTL_SECONDS = 28800;
    private static final long BACKDATE_SECONDS = 60;

    private final ProjectAccessGuard guard;
    private final ProjectMapper projectMapper;
    private final RtCaKeyMapper caMapper;
    private final RtTargetMapper targetMapper;
    private final RtProfileMapper profileMapper;
    private final RtGrantMapper grantMapper;
    private final RtCertificateMapper certificateMapper;
    private final CaKeyCipher cipher;
    private final RemoteTerminalRateLimiter rateLimiter;
    private final RemoteTerminalAuditService audit;
    private final Clock clock;

    @Autowired
    public RemoteTerminalCertificateService(ProjectAccessGuard guard, ProjectMapper projectMapper,
                                            RtCaKeyMapper caMapper, RtTargetMapper targetMapper,
                                            RtProfileMapper profileMapper, RtGrantMapper grantMapper,
                                            RtCertificateMapper certificateMapper, CaKeyCipher cipher,
                                            RemoteTerminalRateLimiter rateLimiter, RemoteTerminalAuditService audit) {
        this(guard, projectMapper, caMapper, targetMapper, profileMapper, grantMapper, certificateMapper, cipher,
                rateLimiter, audit, Clock.systemUTC());
    }

    RemoteTerminalCertificateService(ProjectAccessGuard guard, ProjectMapper projectMapper, RtCaKeyMapper caMapper,
                                     RtTargetMapper targetMapper, RtProfileMapper profileMapper,
                                     RtGrantMapper grantMapper, RtCertificateMapper certificateMapper,
                                     CaKeyCipher cipher, RemoteTerminalRateLimiter rateLimiter, RemoteTerminalAuditService audit, Clock clock) {
        this.guard = guard;
        this.projectMapper = projectMapper;
        this.caMapper = caMapper;
        this.targetMapper = targetMapper;
        this.profileMapper = profileMapper;
        this.grantMapper = grantMapper;
        this.certificateMapper = certificateMapper;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.clock = clock;
    }

    /** 只返回当前用户被授权的身份；没有任何授权的目标不出现在目录里。 */
    public Catalog catalog(String projectIdentifier, Long userId) {
        Project project = requireMemberProject(projectIdentifier, userId);
        List<RtGrant> grants = grantMapper.selectList(new LambdaQueryWrapper<RtGrant>().eq(RtGrant::getUserId, userId));
        if (grants.isEmpty()) {
            return new Catalog(project.getIdentifier(), List.of());
        }
        Map<Long, RtProfile> profiles = profileMapper.selectList(new LambdaQueryWrapper<RtProfile>()
                        .in(RtProfile::getId, grants.stream().map(RtGrant::getProfileRef).toList())
                        .isNull(RtProfile::getDeletedAt).orderByAsc(RtProfile::getId))
                .stream().collect(Collectors.toMap(RtProfile::getId, p -> p, (a, b) -> a, java.util.LinkedHashMap::new));
        if (profiles.isEmpty()) {
            return new Catalog(project.getIdentifier(), List.of());
        }
        Map<Long, List<RtProfile>> byTarget = profiles.values().stream()
                .collect(Collectors.groupingBy(RtProfile::getTargetRef, java.util.LinkedHashMap::new, Collectors.toList()));
        List<CatalogTarget> targets = targetMapper.selectList(new LambdaQueryWrapper<RtTarget>()
                        .in(RtTarget::getId, byTarget.keySet()).eq(RtTarget::getProjectId, project.getId())
                        .isNull(RtTarget::getDeletedAt).orderByAsc(RtTarget::getId))
                .stream().map(t -> new CatalogTarget(t.getTargetId(), t.getAlias(), t.getEnvironment(), t.getHost(),
                        t.getPort(), t.getHostKey(), t.getHostKeyRev(),
                        byTarget.get(t.getId()).stream().map(p -> new CatalogProfile(p.getProfileId(),
                                p.getLoginUser(), p.getMaxTtlSeconds(), p.getAuthType())).toList())).toList();
        return new Catalog(project.getIdentifier(), targets);
    }

    @Transactional(rollbackFor = Exception.class)
    public CertificateResponse issue(Long userId, String clientIp, String oauthClientId, CertificateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (!cipher.isConfigured()) {
            throw new UnprocessableEntityException("远程终端未启用：服务端缺少有效的 RT_CA_KEK");
        }
        rateLimiter.acquire(userId);
        AuthorizedProfile access = authorize(userId, request.project(), request.targetId(), request.profileId(), request.ttlSeconds());
        Project project = access.project();
        RtTarget target = access.target();
        RtProfile profile = access.profile();
        if (!"CERTIFICATE".equals(profile.getAuthType())) {
            throw new IllegalArgumentException("该身份不使用证书认证");
        }
        int ttl = request.ttlSeconds();
        byte[] userKey = SshKeys.parseEd25519(request.publicKey());
        RtCaKey ca = caMapper.selectOne(new LambdaQueryWrapper<RtCaKey>()
                .eq(RtCaKey::getProjectId, project.getId()).eq(RtCaKey::getStatus, "active")
                .orderByDesc(RtCaKey::getId).last("LIMIT 1"));
        if (ca == null) {
            throw new UnprocessableEntityException("项目尚未创建 CA，请联系项目创建者");
        }
        SshCertificateSigner.CaKeyPair pair = new SshCertificateSigner.CaKeyPair(
                cipher.decrypt(project.getId(), ca.getPrivateKeyEnc()),
                SshKeys.parseEd25519(ca.getPublicKey()));

        Instant now = clock.instant();
        Instant validAfter = now.minusSeconds(BACKDATE_SECONDS);
        Instant validBefore = now.plusSeconds(ttl);
        String principal = principal(project.getIdentifier(), target.getTargetId(), profile.getLoginUser());

        RtCertificate row = new RtCertificate();
        row.setProjectId(project.getId());
        row.setCaRef(ca.getId());
        row.setUserId(userId);
        row.setTargetRef(target.getId());
        row.setProfileRef(profile.getId());
        row.setPrincipal(principal);
        row.setKeyFingerprint(SshKeys.fingerprint(SshKeys.blob("ssh-ed25519", userKey)));
        row.setValidAfter(local(validAfter));
        row.setValidBefore(local(validBefore));
        row.setClientIp(truncate(clientIp, 64));
        row.setOauthClientId(truncate(oauthClientId, 128));
        row.setCreatedAt(BeijingTime.now());
        certificateMapper.insert(row);

        String certificate = SshCertificateSigner.issue(pair, userKey, new SshCertificateSigner.Request(
                row.getId(), "u" + userId + "/" + project.getIdentifier() + "/" + target.getTargetId() + "/"
                + profile.getProfileId(), List.of(principal), validAfter, validBefore,
                Boolean.TRUE.equals(profile.getPermitPty())));
        return new CertificateResponse(certificate, row.getId(), principal, validBefore.toString());
    }

    /** 目录与凭据使用相同授权检查；仅将密码交给被授权的客户端。 */
    @Transactional(rollbackFor = Exception.class)
    public PasswordResponse password(Long userId, PasswordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (!cipher.isConfigured()) {
            throw new UnprocessableEntityException("远程终端未启用：服务端缺少有效的 RT_CA_KEK");
        }
        rateLimiter.acquire(userId);
        AuthorizedProfile access = authorize(userId, request.project(), request.targetId(), request.profileId(), request.ttlSeconds());
        if (!"PASSWORD".equals(access.profile().getAuthType())) {
            throw new IllegalArgumentException("该身份不使用密码认证");
        }
        if (request.expectedHostKeyRev() == null || !request.expectedHostKeyRev().equals(access.target().getHostKeyRev())) {
            throw new ConflictOperationException("服务器主机公钥已变更，请重新获取并核对目录");
        }
        if (access.profile().getPasswordCipher() == null) {
            throw new UnprocessableEntityException("该身份未配置密码");
        }
        String password = cipher.decryptPassword(access.project().getId(), access.profile().getId(),
                access.profile().getPasswordCipher());
        audit.record(access.project().getId(), userId, "password.issue",
                access.target().getTargetId() + "/" + access.profile().getProfileId(), null,
                Map.of("profileRef", access.profile().getId(), "ttlSeconds", request.ttlSeconds()));
        return new PasswordResponse(password, Boolean.TRUE.equals(access.profile().getPermitPty()));
    }

    private record AuthorizedProfile(Project project, RtTarget target, RtProfile profile) { }

    private AuthorizedProfile authorize(Long userId, String projectIdentifier, String targetId, String profileId, Integer ttlSeconds) {
        Project project = requireMemberProject(projectIdentifier, userId);
        RtTarget target = targetMapper.selectOne(new LambdaQueryWrapper<RtTarget>()
                .eq(RtTarget::getProjectId, project.getId()).eq(RtTarget::getTargetId, targetId)
                .isNull(RtTarget::getDeletedAt));
        RtProfile profile = target == null ? null : profileMapper.selectOne(new LambdaQueryWrapper<RtProfile>()
                .eq(RtProfile::getTargetRef, target.getId()).eq(RtProfile::getProfileId, profileId)
                .isNull(RtProfile::getDeletedAt));
        Long granted = profile == null ? 0L : grantMapper.selectCount(new LambdaQueryWrapper<RtGrant>()
                .eq(RtGrant::getProfileRef, profile.getId()).eq(RtGrant::getUserId, userId));
        if (target == null || profile == null || granted == null || granted == 0) {
            throw new ForbiddenOperationException("没有使用该服务器身份的授权");
        }
        int ttl = ttlSeconds == null ? 0 : ttlSeconds;
        if (ttl < 1 || ttl > Math.min(MAX_TTL_SECONDS, profile.getMaxTtlSeconds())) {
            throw new IllegalArgumentException("连接期限超出该身份允许的范围（1–" + profile.getMaxTtlSeconds() + " 秒）");
        }
        return new AuthorizedProfile(project, target, profile);
    }

    static String principal(String projectIdentifier, String targetId, String loginUser) {
        return "rt." + projectIdentifier + "." + targetId + "." + loginUser;
    }

    private Project requireMemberProject(String identifier, Long userId) {
        String normalized = identifier == null ? "" : identifier.trim().toUpperCase();
        Project project = normalized.isEmpty() ? null : projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getIdentifier, normalized));
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + identifier);
        }
        RemoteTerminalAdminService.requireSignablePrincipalPrefix(project);
        guard.requireMember(project.getId(), userId);
        return project;
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, BeijingTime.ZONE_ID);
    }

    private static String truncate(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
