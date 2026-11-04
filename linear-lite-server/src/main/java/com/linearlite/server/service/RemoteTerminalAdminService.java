package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.RemoteTerminalDtos.AuditRecord;
import com.linearlite.server.dto.RemoteTerminalDtos.CaView;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateRecord;
import com.linearlite.server.dto.RemoteTerminalDtos.Overview;
import com.linearlite.server.dto.RemoteTerminalDtos.ProfileRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.ProfileView;
import com.linearlite.server.dto.RemoteTerminalDtos.TargetRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.TargetView;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.RtCaKey;
import com.linearlite.server.entity.RtCertificate;
import com.linearlite.server.entity.RtGrant;
import com.linearlite.server.entity.RtProfile;
import com.linearlite.server.entity.RtTarget;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.exception.UnprocessableEntityException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.RtAuditEventMapper;
import com.linearlite.server.mapper.RtCaKeyMapper;
import com.linearlite.server.mapper.RtCertificateMapper;
import com.linearlite.server.mapper.RtGrantMapper;
import com.linearlite.server.mapper.RtProfileMapper;
import com.linearlite.server.mapper.RtTargetMapper;
import com.linearlite.server.sshca.CaKeyCipher;
import com.linearlite.server.sshca.SshCertificateSigner;
import com.linearlite.server.sshca.SshKeys;
import com.linearlite.server.time.BeijingTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 项目创建者维护团队服务器配置：CA、目标、登录身份与授权。
 * 所有写操作写变更日志；target_id、login_user 不允许含点号，保证 principal 的各段可无歧义拆分。
 */
@Service
public class RemoteTerminalAdminService {

    static final Pattern CATALOG_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    static final Pattern LOGIN_USER = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    static final Pattern PROJECT_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");
    private static final Pattern HOST = Pattern.compile("^[^\\s\\x00]{1,256}$");

    private final ProjectAccessGuard guard;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final RtCaKeyMapper caMapper;
    private final RtTargetMapper targetMapper;
    private final RtProfileMapper profileMapper;
    private final RtGrantMapper grantMapper;
    private final RtCertificateMapper certificateMapper;
    private final RtAuditEventMapper auditMapper;
    private final CaKeyCipher cipher;
    private final RemoteTerminalAuditService audit;

    public RemoteTerminalAdminService(ProjectAccessGuard guard, ProjectMapper projectMapper,
                                      ProjectMemberMapper memberMapper, RtCaKeyMapper caMapper,
                                      RtTargetMapper targetMapper, RtProfileMapper profileMapper,
                                      RtGrantMapper grantMapper, RtCertificateMapper certificateMapper,
                                      RtAuditEventMapper auditMapper, CaKeyCipher cipher,
                                      RemoteTerminalAuditService audit) {
        this.guard = guard;
        this.projectMapper = projectMapper;
        this.memberMapper = memberMapper;
        this.caMapper = caMapper;
        this.targetMapper = targetMapper;
        this.profileMapper = profileMapper;
        this.grantMapper = grantMapper;
        this.certificateMapper = certificateMapper;
        this.auditMapper = auditMapper;
        this.cipher = cipher;
        this.audit = audit;
    }

    // ---------- 概览 ----------

    public Overview overview(Long projectId, Long userId) {
        guard.requireOwner(projectId, userId);
        Project project = project(projectId);
        List<CaView> cas = caMapper.selectList(new LambdaQueryWrapper<RtCaKey>()
                        .eq(RtCaKey::getProjectId, projectId).ne(RtCaKey::getStatus, "retired")
                        .orderByAsc(RtCaKey::getId))
                .stream().map(RemoteTerminalAdminService::caView).toList();
        List<RtTarget> targets = liveTargets(projectId);
        Map<Long, List<RtProfile>> profiles = liveProfiles(targets).stream()
                .collect(Collectors.groupingBy(RtProfile::getTargetRef, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Long>> grants = grantsByProfile(profiles.values().stream().flatMap(List::stream).toList());
        List<TargetView> views = targets.stream().map(target -> targetView(target,
                profiles.getOrDefault(target.getId(), List.of()), grants)).toList();
        return new Overview(cipher.isConfigured(), project.getIdentifier(), cas, views);
    }

    // ---------- CA ----------

    @Transactional(rollbackFor = Exception.class)
    public CaView createCa(Long projectId, Long userId) {
        guard.requireOwner(projectId, userId);
        requireEnabled();
        requireSignablePrincipalPrefix(project(projectId));
        Long active = caMapper.selectCount(new LambdaQueryWrapper<RtCaKey>()
                .eq(RtCaKey::getProjectId, projectId).eq(RtCaKey::getStatus, "active"));
        if (active != null && active > 0) {
            throw new ConflictOperationException("该项目已有可用的 CA；如需更换请使用轮换");
        }
        return insertCa(projectId, userId, "ca.create");
    }

    /** 旧 CA 转为 retiring（接入脚本仍包含它），新 CA 立即用于签发。 */
    @Transactional(rollbackFor = Exception.class)
    public CaView rotateCa(Long projectId, Long userId) {
        guard.requireOwner(projectId, userId);
        requireEnabled();
        RtCaKey current = activeCa(projectId);
        if (current == null) {
            throw new UnprocessableEntityException("项目尚未创建 CA");
        }
        Long retiring = caMapper.selectCount(new LambdaQueryWrapper<RtCaKey>()
                .eq(RtCaKey::getProjectId, projectId).eq(RtCaKey::getStatus, "retiring"));
        if (retiring != null && retiring > 0) {
            throw new ConflictOperationException("上一次轮换尚未完成，请先在所有服务器更新接入脚本并结束旧 CA");
        }
        current.setStatus("retiring");
        caMapper.updateById(current);
        return insertCa(projectId, userId, "ca.rotate");
    }

    @Transactional(rollbackFor = Exception.class)
    public void retireCa(Long projectId, Long caId, Long userId) {
        guard.requireOwner(projectId, userId);
        RtCaKey ca = caMapper.selectById(caId);
        if (ca == null || !projectId.equals(ca.getProjectId())) {
            throw new ResourceNotFoundException("CA 不存在: " + caId);
        }
        if (!"retiring".equals(ca.getStatus())) {
            throw new ConflictOperationException("只能结束处于轮换中的旧 CA");
        }
        ca.setStatus("retired");
        caMapper.updateById(ca);
        audit.record(projectId, userId, "ca.retire", fingerprint(ca), caView(ca), null);
    }

    // ---------- 目标 ----------

    @Transactional(rollbackFor = Exception.class)
    public TargetView createTarget(Long projectId, Long userId, TargetRequest request) {
        guard.requireOwner(projectId, userId);
        Validated v = validateTarget(request, true);
        Long existing = targetMapper.selectCount(new LambdaQueryWrapper<RtTarget>()
                .eq(RtTarget::getProjectId, projectId).eq(RtTarget::getTargetId, v.targetId)
                .isNull(RtTarget::getDeletedAt));
        if (existing != null && existing > 0) {
            throw new ConflictOperationException("target_id 已存在: " + v.targetId);
        }
        RtTarget target = new RtTarget();
        target.setProjectId(projectId);
        target.setTargetId(v.targetId);
        apply(target, v);
        target.setHostKeyRev(1);
        target.setCreatedAt(BeijingTime.now());
        target.setUpdatedAt(BeijingTime.now());
        targetMapper.insert(target);
        audit.record(projectId, userId, "target.create", v.targetId, null, targetAudit(target));
        return targetView(target, List.of(), Map.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public TargetView updateTarget(Long projectId, Long targetRef, Long userId, TargetRequest request) {
        guard.requireOwner(projectId, userId);
        RtTarget target = ownedTarget(projectId, targetRef);
        Validated v = validateTarget(request, false);
        Map<String, Object> before = targetAudit(target);
        apply(target, v);
        if (!v.hostKey.normalized().equals(before.get("hostKey"))) {
            target.setHostKeyRev(target.getHostKeyRev() + 1);
        }
        target.setUpdatedAt(BeijingTime.now());
        targetMapper.updateById(target);
        audit.record(projectId, userId, "target.update", target.getTargetId(), before, targetAudit(target));
        List<RtProfile> profiles = liveProfiles(List.of(target));
        return targetView(target, profiles, grantsByProfile(profiles));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTarget(Long projectId, Long targetRef, Long userId) {
        guard.requireOwner(projectId, userId);
        RtTarget target = ownedTarget(projectId, targetRef);
        for (RtProfile profile : liveProfiles(List.of(target))) {
            softDeleteProfile(profile);
        }
        target.setDeletedAt(BeijingTime.now());
        targetMapper.updateById(target);
        audit.record(projectId, userId, "target.delete", target.getTargetId(), targetAudit(target), null);
    }

    // ---------- 登录身份 ----------

    @Transactional(rollbackFor = Exception.class)
    public ProfileView createProfile(Long projectId, Long targetRef, Long userId, ProfileRequest request) {
        guard.requireOwner(projectId, userId);
        RtTarget target = ownedTarget(projectId, targetRef);
        ProfileFields f = validateProfile(request);
        for (RtProfile other : liveProfiles(List.of(target))) {
            if (other.getProfileId().equals(f.profileId)) {
                throw new ConflictOperationException("profile_id 已存在: " + f.profileId);
            }
            if (other.getLoginUser().equals(f.loginUser)) {
                throw new ConflictOperationException("该服务器已有登录账号 " + f.loginUser + " 的身份");
            }
        }
        RtProfile profile = new RtProfile();
        profile.setTargetRef(targetRef);
        profile.setProfileId(f.profileId);
        profile.setLoginUser(f.loginUser);
        profile.setMaxTtlSeconds(f.maxTtlSeconds);
        profile.setPermitPty(f.permitPty);
        profile.setCreatedAt(BeijingTime.now());
        profileMapper.insert(profile);
        audit.record(projectId, userId, "profile.create", target.getTargetId() + "/" + f.profileId, null, profileAudit(profile));
        return profileView(profile, List.of());
    }

    /** login_user 创建后不可改：它决定服务器上的 principals 文件，改动等于换了一个身份。 */
    @Transactional(rollbackFor = Exception.class)
    public ProfileView updateProfile(Long projectId, Long profileRef, Long userId, ProfileRequest request) {
        guard.requireOwner(projectId, userId);
        RtProfile profile = ownedProfile(projectId, profileRef);
        RtTarget target = targetMapper.selectById(profile.getTargetRef());
        ProfileFields f = validateProfile(request);
        if (!profile.getProfileId().equals(f.profileId) || !profile.getLoginUser().equals(f.loginUser)) {
            throw new IllegalArgumentException("profile_id 与登录账号创建后不可修改，请新建身份");
        }
        Map<String, Object> before = profileAudit(profile);
        profile.setMaxTtlSeconds(f.maxTtlSeconds);
        profile.setPermitPty(f.permitPty);
        profileMapper.updateById(profile);
        audit.record(projectId, userId, "profile.update", target.getTargetId() + "/" + f.profileId, before, profileAudit(profile));
        return profileView(profile, grantsByProfile(List.of(profile)).getOrDefault(profile.getId(), List.of()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProfile(Long projectId, Long profileRef, Long userId) {
        guard.requireOwner(projectId, userId);
        RtProfile profile = ownedProfile(projectId, profileRef);
        RtTarget target = targetMapper.selectById(profile.getTargetRef());
        Map<String, Object> before = profileAudit(profile);
        softDeleteProfile(profile);
        audit.record(projectId, userId, "profile.delete", target.getTargetId() + "/" + profile.getProfileId(), before, null);
    }

    // ---------- 授权 ----------

    @Transactional(rollbackFor = Exception.class)
    public List<Long> setGrants(Long projectId, Long profileRef, Long userId, List<Long> requested) {
        guard.requireOwner(projectId, userId);
        RtProfile profile = ownedProfile(projectId, profileRef);
        RtTarget target = targetMapper.selectById(profile.getTargetRef());
        Set<Long> wanted = new LinkedHashSet<>(requested == null ? List.of() : requested);
        if (wanted.size() > 500 || wanted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("授权名单无效");
        }
        if (!wanted.isEmpty()) {
            Long members = memberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, projectId).in(ProjectMember::getUserId, wanted));
            if (members == null || members != wanted.size()) {
                throw new IllegalArgumentException("授权对象必须是项目成员");
            }
        }
        List<RtGrant> current = grantMapper.selectList(new LambdaQueryWrapper<RtGrant>()
                .eq(RtGrant::getProfileRef, profileRef));
        Set<Long> before = current.stream().map(RtGrant::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        for (RtGrant grant : current) {
            if (!wanted.contains(grant.getUserId())) {
                grantMapper.deleteById(grant.getId());
            }
        }
        for (Long wantedUser : wanted) {
            if (!before.contains(wantedUser)) {
                RtGrant grant = new RtGrant();
                grant.setProfileRef(profileRef);
                grant.setUserId(wantedUser);
                grant.setGrantedBy(userId);
                grant.setCreatedAt(BeijingTime.now());
                grantMapper.insert(grant);
            }
        }
        audit.record(projectId, userId, "grants.set", target.getTargetId() + "/" + profile.getProfileId(),
                Map.of("userIds", before), Map.of("userIds", wanted));
        return new ArrayList<>(wanted);
    }

    // ---------- 接入脚本与记录 ----------

    public String accessScript(Long projectId, Long targetRef, Long userId) {
        guard.requireOwner(projectId, userId);
        Project project = project(projectId);
        RtTarget target = ownedTarget(projectId, targetRef);
        List<String> cas = caMapper.selectList(new LambdaQueryWrapper<RtCaKey>()
                        .eq(RtCaKey::getProjectId, projectId).in(RtCaKey::getStatus, "active", "retiring")
                        .orderByAsc(RtCaKey::getId))
                .stream().map(RtCaKey::getPublicKey).toList();
        if (cas.isEmpty()) {
            throw new UnprocessableEntityException("项目尚未创建 CA");
        }
        List<RtProfile> profiles = liveProfiles(List.of(target));
        if (profiles.isEmpty()) {
            throw new UnprocessableEntityException("该服务器还没有登录身份");
        }
        return AccessScript.render(project.getIdentifier(), target.getTargetId(), cas,
                profiles.stream().map(RtProfile::getLoginUser).toList());
    }

    public List<CertificateRecord> certificates(Long projectId, Long userId, int limit) {
        guard.requireOwner(projectId, userId);
        List<RtCertificate> rows = certificateMapper.selectList(new LambdaQueryWrapper<RtCertificate>()
                .eq(RtCertificate::getProjectId, projectId).orderByDesc(RtCertificate::getId)
                .last("LIMIT " + clamp(limit)));
        Map<Long, RtTarget> targets = new LinkedHashMap<>();
        Map<Long, RtProfile> profiles = new LinkedHashMap<>();
        for (RtCertificate row : rows) {
            targets.computeIfAbsent(row.getTargetRef(), targetMapper::selectById);
            profiles.computeIfAbsent(row.getProfileRef(), profileMapper::selectById);
        }
        return rows.stream().map(row -> new CertificateRecord(row.getId(), row.getUserId(),
                name(targets.get(row.getTargetRef()), RtTarget::getTargetId),
                name(profiles.get(row.getProfileRef()), RtProfile::getProfileId), row.getPrincipal(),
                row.getKeyFingerprint(), row.getValidAfter(), row.getValidBefore(), row.getClientIp(),
                row.getCreatedAt())).toList();
    }

    public List<AuditRecord> auditEvents(Long projectId, Long userId, int limit) {
        guard.requireOwner(projectId, userId);
        return auditMapper.selectList(new LambdaQueryWrapper<com.linearlite.server.entity.RtAuditEvent>()
                        .eq(com.linearlite.server.entity.RtAuditEvent::getProjectId, projectId)
                        .orderByDesc(com.linearlite.server.entity.RtAuditEvent::getId)
                        .last("LIMIT " + clamp(limit)))
                .stream().map(e -> new AuditRecord(e.getId(), e.getActorId(), e.getAction(), e.getSubject(),
                        e.getBeforeJson(), e.getAfterJson(), e.getCreatedAt())).toList();
    }

    // ---------- 内部 ----------

    private CaView insertCa(Long projectId, Long userId, String action) {
        SshCertificateSigner.CaKeyPair pair = SshCertificateSigner.generateCaKeyPair();
        RtCaKey ca = new RtCaKey();
        ca.setProjectId(projectId);
        ca.setPublicKey(SshKeys.formatEd25519(pair.publicKey()) + " linear-lite " + project(projectId).getIdentifier());
        ca.setPrivateKeyEnc(cipher.encrypt(projectId, pair.seed()));
        ca.setStatus("active");
        ca.setCreatedAt(BeijingTime.now());
        caMapper.insert(ca);
        audit.record(projectId, userId, action, fingerprint(ca), null, caView(ca));
        return caView(ca);
    }

    RtCaKey activeCa(Long projectId) {
        return caMapper.selectOne(new LambdaQueryWrapper<RtCaKey>()
                .eq(RtCaKey::getProjectId, projectId).eq(RtCaKey::getStatus, "active")
                .orderByDesc(RtCaKey::getId).last("LIMIT 1"));
    }

    private void requireEnabled() {
        if (!cipher.isConfigured()) {
            throw new UnprocessableEntityException("远程终端未启用：服务端缺少有效的 RT_CA_KEK（32 字节 Base64）");
        }
    }

    static void requireSignablePrincipalPrefix(Project project) {
        if (!PROJECT_IDENTIFIER.matcher(project.getIdentifier()).matches()) {
            throw new UnprocessableEntityException("项目标识含有不支持的字符，无法用于证书 principal");
        }
    }

    private Project project(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + projectId);
        }
        return project;
    }

    private RtTarget ownedTarget(Long projectId, Long targetRef) {
        RtTarget target = targetMapper.selectById(targetRef);
        if (target == null || target.getDeletedAt() != null || !projectId.equals(target.getProjectId())) {
            throw new ResourceNotFoundException("服务器不存在: " + targetRef);
        }
        return target;
    }

    private RtProfile ownedProfile(Long projectId, Long profileRef) {
        RtProfile profile = profileMapper.selectById(profileRef);
        if (profile == null || profile.getDeletedAt() != null) {
            throw new ResourceNotFoundException("登录身份不存在: " + profileRef);
        }
        ownedTarget(projectId, profile.getTargetRef());
        return profile;
    }

    private List<RtTarget> liveTargets(Long projectId) {
        return targetMapper.selectList(new LambdaQueryWrapper<RtTarget>()
                .eq(RtTarget::getProjectId, projectId).isNull(RtTarget::getDeletedAt).orderByAsc(RtTarget::getId));
    }

    private List<RtProfile> liveProfiles(List<RtTarget> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        return profileMapper.selectList(new LambdaQueryWrapper<RtProfile>()
                .in(RtProfile::getTargetRef, targets.stream().map(RtTarget::getId).toList())
                .isNull(RtProfile::getDeletedAt).orderByAsc(RtProfile::getId));
    }

    private Map<Long, List<Long>> grantsByProfile(List<RtProfile> profiles) {
        if (profiles.isEmpty()) {
            return Map.of();
        }
        return grantMapper.selectList(new LambdaQueryWrapper<RtGrant>()
                        .in(RtGrant::getProfileRef, profiles.stream().map(RtProfile::getId).toList())
                        .orderByAsc(RtGrant::getId))
                .stream().collect(Collectors.groupingBy(RtGrant::getProfileRef, LinkedHashMap::new,
                        Collectors.mapping(RtGrant::getUserId, Collectors.toList())));
    }

    private void softDeleteProfile(RtProfile profile) {
        grantMapper.delete(new LambdaQueryWrapper<RtGrant>().eq(RtGrant::getProfileRef, profile.getId()));
        profile.setDeletedAt(BeijingTime.now());
        profileMapper.updateById(profile);
    }

    private static TargetView targetView(RtTarget t, List<RtProfile> profiles, Map<Long, List<Long>> grants) {
        String fingerprint = SshKeys.parseHostKey(t.getHostKey()).fingerprint();
        return new TargetView(t.getId(), t.getTargetId(), t.getAlias(), t.getEnvironment(), t.getHost(), t.getPort(),
                t.getHostKey(), fingerprint, t.getHostKeyRev(),
                profiles.stream().map(p -> profileView(p, grants.getOrDefault(p.getId(), List.of()))).toList());
    }

    private static ProfileView profileView(RtProfile p, List<Long> grantUserIds) {
        return new ProfileView(p.getId(), p.getProfileId(), p.getLoginUser(), p.getMaxTtlSeconds(),
                Boolean.TRUE.equals(p.getPermitPty()), grantUserIds);
    }

    private static CaView caView(RtCaKey ca) {
        return new CaView(ca.getId(), ca.getStatus(), ca.getPublicKey(), fingerprint(ca), ca.getCreatedAt());
    }

    private static String fingerprint(RtCaKey ca) {
        return SshKeys.parseHostKey(ca.getPublicKey()).fingerprint();
    }

    private static Map<String, Object> targetAudit(RtTarget t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("targetId", t.getTargetId());
        map.put("alias", t.getAlias());
        map.put("environment", t.getEnvironment());
        map.put("host", t.getHost());
        map.put("port", t.getPort());
        map.put("hostKey", SshKeys.parseHostKey(t.getHostKey()).normalized());
        map.put("hostKeyFingerprint", SshKeys.parseHostKey(t.getHostKey()).fingerprint());
        map.put("hostKeyRev", t.getHostKeyRev());
        return map;
    }

    private static Map<String, Object> profileAudit(RtProfile p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profileId", p.getProfileId());
        map.put("loginUser", p.getLoginUser());
        map.put("maxTtlSeconds", p.getMaxTtlSeconds());
        map.put("permitPty", p.getPermitPty());
        return map;
    }

    private record Validated(String targetId, String alias, String environment, String host, int port,
                             SshKeys.PublicKey hostKey) {
    }

    private record ProfileFields(String profileId, String loginUser, int maxTtlSeconds, boolean permitPty) {
    }

    private static void apply(RtTarget target, Validated v) {
        target.setAlias(v.alias);
        target.setEnvironment(v.environment);
        target.setHost(v.host);
        target.setPort(v.port);
        target.setHostKey(v.hostKey.normalized());
    }

    private static Validated validateTarget(TargetRequest r, boolean creating) {
        if (r == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String targetId = trim(r.targetId());
        if (creating && !CATALOG_ID.matcher(targetId).matches()) {
            throw new IllegalArgumentException("target_id 只能包含字母、数字、下划线和连字符（最长 100）");
        }
        String alias = text(r.alias(), "名称");
        String environment = text(r.environment(), "环境");
        String host = trim(r.host());
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("服务器地址无效");
        }
        int port = r.port() == null ? 22 : r.port();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口无效");
        }
        return new Validated(targetId, alias, environment, host, port, SshKeys.parseHostKey(r.hostKey()));
    }

    private static ProfileFields validateProfile(ProfileRequest r) {
        if (r == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String profileId = trim(r.profileId());
        if (!CATALOG_ID.matcher(profileId).matches()) {
            throw new IllegalArgumentException("profile_id 只能包含字母、数字、下划线和连字符（最长 100）");
        }
        String loginUser = trim(r.loginUser());
        if (!LOGIN_USER.matcher(loginUser).matches()) {
            throw new IllegalArgumentException("登录账号只能包含字母、数字、下划线和连字符（最长 32）");
        }
        int ttl = r.maxTtlSeconds() == null ? 3600 : r.maxTtlSeconds();
        if (ttl < 1 || ttl > 28800) {
            throw new IllegalArgumentException("最长连接期限须在 1–28800 秒之间");
        }
        return new ProfileFields(profileId, loginUser, ttl, r.permitPty() == null || r.permitPty());
    }

    private static String text(String value, String label) {
        String trimmed = trim(value);
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            throw new IllegalArgumentException(label + "不能为空且不超过 256 个字符");
        }
        return trimmed;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static <T> String name(T row, java.util.function.Function<T, String> getter) {
        return row == null ? "" : getter.apply(row);
    }

    @SuppressWarnings("unused")
    private static LocalDateTime now() {
        return BeijingTime.now();
    }
}
