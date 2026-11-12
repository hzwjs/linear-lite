package com.linearlite.server.service;

import com.linearlite.server.common.HostProtocol;

import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.HostDeviceAuthorizationMapper;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 签名只引用持久批次；业务操作在调用方事务内校验当前归属与租约。 */
@Service
public class CollaborationBatchAuthorizationService {
    private static final String SUBJECT = "collaboration-batch";
    private static final String ISSUER = "linear-lite-collaboration";
    private static final String AUDIENCE = "linear-lite-collaboration-batch";
    private static final String PURPOSE = "stigmergy-batch-v" + HostProtocol.VERSION;
    private static final String ACCEPT_RECOVERY = "stigmergy-accept-recovery-v" + HostProtocol.VERSION;
    private static final String INVALID_BATCH = "批次授权无效或过期";

    private final ProjectDeviceBindingService bindingService;
    private final HostDeviceAuthorizationMapper hosts;
    private final ProjectMapper projects;
    private final ProjectDeviceBindingMapper bindings;
    private final CollaborationBatchMapper batches;
    private final TaskMapper tasks;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    @Autowired
    public CollaborationBatchAuthorizationService(ProjectDeviceBindingService bindingService,
            HostDeviceAuthorizationMapper hosts, ProjectMapper projects, ProjectDeviceBindingMapper bindings,
            CollaborationBatchMapper batches, TaskMapper tasks, JwtProperties jwtProperties) {
        this(bindingService, hosts, projects, bindings, batches, tasks, jwtProperties, Clock.systemUTC());
    }

    CollaborationBatchAuthorizationService(ProjectDeviceBindingService bindingService,
            HostDeviceAuthorizationMapper hosts, ProjectMapper projects, ProjectDeviceBindingMapper bindings,
            CollaborationBatchMapper batches, TaskMapper tasks, JwtProperties jwtProperties, Clock clock) {
        this.bindingService = bindingService;
        this.hosts = hosts;
        this.projects = projects;
        this.bindings = bindings;
        this.batches = batches;
        this.tasks = tasks;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /** 调用方持有授权、项目、绑定和批次锁，签名不能创建新批次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedBatch issueAccepted(CollaborationBatch batch, ProjectDeviceBinding binding,
                                     HostDeviceAuthorization grant) {
        LocalDateTime now = now();
        if (batch == null || binding == null || grant == null || grant.getRevokedAt() != null
                || !Objects.equals(batch.getUserId(), grant.getUserId())
                || !Objects.equals(batch.getHostAuthorizationId(), grant.getId())
                || !Objects.equals(batch.getBindingId(), binding.getId())
                || !Objects.equals(batch.getBindingRevision(), binding.getRevision())
                || !Objects.equals(batch.getUserId(), binding.getUserId())
                || !Objects.equals(batch.getProjectId(), binding.getProjectId())
                || !Objects.equals(grant.getDeviceId(), binding.getDeviceId())
                || !"accepted".equals(batch.getLifecycle()) || !"RUN".equals(batch.getDesiredIntent())
                || batch.getHostInstanceId() == null || batch.getHostInstanceId().isBlank()
                || batch.getRunEpoch() == null || batch.getRunEpoch() <= 0
                || batch.getRunLeaseUntil() == null || !batch.getRunLeaseUntil().isAfter(now)
                || batch.getDeadlineAt() == null || !batch.getDeadlineAt().isAfter(now)) {
            throw denied();
        }
        Instant deadline = batch.getDeadlineAt().toInstant(ZoneOffset.UTC);
        // JWT 秒精度向上取整，批次截止时间仍由数据库门禁裁决。
        Instant expiresAt = Instant.ofEpochSecond(deadline.getEpochSecond() + (deadline.getNano() == 0 ? 0 : 1));
        String token = Jwts.builder().subject(SUBJECT).issuer(ISSUER).audience().add(AUDIENCE).and()
                .claim("purpose", PURPOSE).claim("batchId", batch.getBatchId())
                .claim("userId", batch.getUserId()).claim("projectId", batch.getProjectId())
                .claim("bindingId", binding.getId()).claim("bindingRevision", binding.getRevision())
                .claim("deviceId", binding.getDeviceId()).claim("hostAuthorizationId", grant.getId())
                .claim("hostInstanceId", batch.getHostInstanceId()).claim("runEpoch", batch.getRunEpoch())
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(expiresAt))
                .signWith(signingKey()).compact();
        return new IssuedBatch(batch.getBatchId(), token, expiresAt,
                batch.getRunLeaseUntil().toInstant(ZoneOffset.UTC), deadline, batch.getRunEpoch());
    }

    /** 接受之前签发停止凭据，宿主保存凭据后才发起接受。 */
    PreparedAcceptance prepareAcceptance(HostCollaborationBatchService.Context context, Long userId,
                                         Long grantId, String hostInstanceId) {
        long runEpoch = Math.addExact(context.runEpoch(), 1L);
        Instant issuedAt = clock.instant();
        String token = Jwts.builder().subject(SUBJECT).issuer(ISSUER).audience().add(AUDIENCE).and()
                .claim("purpose", ACCEPT_RECOVERY).claim("batchId", context.batchId())
                .claim("userId", userId).claim("projectId", context.projectId())
                .claim("bindingId", context.bindingId()).claim("bindingRevision", context.bindingRevision())
                .claim("deviceId", context.deviceId()).claim("hostAuthorizationId", grantId)
                .claim("hostInstanceId", hostInstanceId).claim("runEpoch", runEpoch)
                .issuedAt(Date.from(issuedAt)).expiration(Date.from(issuedAt.plusSeconds(60)))
                .signWith(signingKey()).compact();
        return new PreparedAcceptance(context.batchId(), runEpoch, token);
    }

    void verifyPreparedAcceptance(String token, CollaborationBatch batch, String deviceId, String hostInstanceId) {
        TokenIdentity identity = parse(token, false, ACCEPT_RECOVERY);
        if (!matchesPreparedBatch(batch, identity) || !Objects.equals(identity.deviceId(), deviceId)
                || !Objects.equals(identity.hostInstanceId(), hostInstanceId)
                || !Objects.equals(batch.getHostInstanceId(), hostInstanceId)
                || !Objects.equals(batch.getRunEpoch(), identity.runEpoch())
                || !"accepted".equals(batch.getLifecycle())) throw denied();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CollaborationBatch lockPreparedAcceptance(String token) {
        TokenIdentity identity = parse(token, true, ACCEPT_RECOVERY);
        HostDeviceAuthorization grant = hosts.selectOwnedForUpdate(identity.userId(), identity.hostAuthorizationId());
        if (grant == null || !Objects.equals(grant.getDeviceId(), identity.deviceId())
                || projects.lockProject(identity.projectId()) == null) throw denied();
        CollaborationBatch batch = batches.selectForUpdate(identity.batchId());
        if (!matchesPreparedBatch(batch, identity)) throw denied();
        boolean preAcceptance = batch.getHostInstanceId() == null
                && batch.getRunEpoch() != null && batch.getRunEpoch() == identity.runEpoch() - 1
                && ("queued".equals(batch.getLifecycle()) || "ended".equals(batch.getLifecycle()));
        boolean accepted = Objects.equals(batch.getHostInstanceId(), identity.hostInstanceId())
                && Objects.equals(batch.getRunEpoch(), identity.runEpoch())
                && ("accepted".equals(batch.getLifecycle()) || "ended".equals(batch.getLifecycle()));
        if (!preAcceptance && !accepted) throw denied();
        return batch;
    }

    private boolean matchesPreparedBatch(CollaborationBatch batch, TokenIdentity identity) {
        return batch != null && Objects.equals(batch.getUserId(), identity.userId())
                && Objects.equals(batch.getBatchId(), identity.batchId())
                && Objects.equals(batch.getProjectId(), identity.projectId())
                && Objects.equals(batch.getHostAuthorizationId(), identity.hostAuthorizationId())
                && Objects.equals(batch.getBindingId(), identity.bindingId())
                && Objects.equals(batch.getBindingRevision(), identity.bindingRevision());
    }

    public record PreparedAcceptance(String batchId, Long runEpoch, String recoveryToken) {
        @Override public String toString() {
            return "PreparedAcceptance[batchId=" + batchId + ", runEpoch=" + runEpoch + ", recoveryToken=<redacted>]";
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BatchContext observeBatch(String token, Long userId, Long bindingId, String deviceId,
                                     String projectDirectory) {
        return authorize(token, userId, bindingId, deviceId, projectDirectory, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BatchContext authorizeTask(String token, Long userId, Long bindingId, String deviceId,
                                      String projectDirectory, Long taskId) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("任务标识无效");
        return authorize(token, userId, bindingId, deviceId, projectDirectory, taskId);
    }

    private BatchContext authorize(String token, Long userId, Long bindingId, String deviceId,
                                   String projectDirectory, Long taskId) {
        TokenIdentity identity = parse(token, false);
        if (!Objects.equals(identity.userId(), userId) || !Objects.equals(identity.bindingId(), bindingId)
                || !Objects.equals(identity.deviceId(), deviceId)) throw denied();
        BatchContext context = lock(identity, false, false, projectDirectory);
        if (taskId != null) {
            if (!context.taskIds().contains(taskId)) throw denied();
            Task task = tasks.selectById(taskId);
            if (task == null || !Objects.equals(task.getProjectId(), context.projectId())) throw denied();
        }
        return context;
    }

    /** 运行租约接口可读取 STOP 意图，但不能在 STOP 后续租。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BatchContext authorizeHostControl(String token, Long userId, Long grantId, String deviceId) {
        TokenIdentity identity = parse(token, false);
        if (!Objects.equals(identity.userId(), userId) || !Objects.equals(identity.hostAuthorizationId(), grantId)
                || !Objects.equals(identity.deviceId(), deviceId)) throw denied();
        ProjectDeviceBinding observed = bindings.selectById(identity.bindingId());
        if (observed == null) throw denied();
        return lock(identity, false, true, observed.getProjectDirectory());
    }

    /** 验证签名只用于恢复控制入口；领域服务继续校验持久归属。 */
    public Long recoveryUserId(String token) { return parse(token, true).userId(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public BatchContext recoveryContext(String token, Long authenticatedUserId) {
        TokenIdentity identity = parse(token, true);
        if (!Objects.equals(identity.userId(), authenticatedUserId)) throw denied();
        return lock(identity, true, true, null);
    }

    /** Host OAuth 与批次签名须属于同一设备授权。 */
    public void requireHostIdentity(String token, Long userId, Long grantId, String deviceId) {
        TokenIdentity identity = parse(token, false);
        if (!Objects.equals(identity.userId(), userId) || !Objects.equals(identity.hostAuthorizationId(), grantId)
                || !Objects.equals(identity.deviceId(), deviceId)) throw denied();
    }

    private BatchContext lock(TokenIdentity identity, boolean recovery, boolean allowStop, String directory) {
        // grant → project → binding → batch；事务贯穿调用方的任务写入。
        HostDeviceAuthorization grant = hosts.selectOwnedForUpdate(identity.userId(), identity.hostAuthorizationId());
        if (grant == null || !Objects.equals(grant.getDeviceId(), identity.deviceId())
                || (!recovery && grant.getRevokedAt() != null)) throw denied();
        ProjectDeviceBinding binding;
        if (recovery) {
            if (projects.lockProject(identity.projectId()) == null) throw denied();
            binding = bindings.selectForUpdate(identity.userId(), identity.projectId(), identity.deviceId());
            if (binding == null || !Objects.equals(binding.getId(), identity.bindingId())
                    || !Objects.equals(binding.getUserId(), identity.userId())
                    || !Objects.equals(binding.getProjectId(), identity.projectId())
                    || !Objects.equals(binding.getDeviceId(), identity.deviceId())) throw denied();
        } else {
            binding = bindingService.requireForBatch(identity.userId(), identity.projectId(), identity.bindingId(),
                    identity.deviceId(), directory);
        }
        CollaborationBatch batch = batches.selectForUpdate(identity.batchId());
        if (batch == null || !Objects.equals(batch.getUserId(), identity.userId())
                || !Objects.equals(batch.getProjectId(), identity.projectId())
                || !Objects.equals(batch.getBindingId(), identity.bindingId())
                || !Objects.equals(batch.getBindingRevision(), identity.bindingRevision())
                || !Objects.equals(batch.getHostAuthorizationId(), identity.hostAuthorizationId())
                || !Objects.equals(batch.getHostInstanceId(), identity.hostInstanceId())
                || !Objects.equals(batch.getRunEpoch(), identity.runEpoch())
                || !("accepted".equals(batch.getLifecycle()) || recovery && "ended".equals(batch.getLifecycle())))
            throw denied();
        if (!Objects.equals(binding.getRevision(), identity.bindingRevision())
                && !(recovery && "ended".equals(batch.getLifecycle()))) throw denied();
        if (!recovery) {
            LocalDateTime now = now();
            if ((!allowStop && !"RUN".equals(batch.getDesiredIntent()))
                    || batch.getRunLeaseUntil() == null || !batch.getRunLeaseUntil().isAfter(now)
                    || batch.getDeadlineAt() == null || !batch.getDeadlineAt().isAfter(now)) throw denied();
        }
        List<Long> taskIds = batches.selectTaskIds(batch.getBatchId());
        if (taskIds == null || taskIds.isEmpty()) throw denied();
        return new BatchContext(identity.batchId(), identity.userId(), identity.projectId(), identity.bindingId(),
                identity.bindingRevision(), identity.deviceId(), List.copyOf(taskIds), identity.expiresAt(),
                identity.hostAuthorizationId(), identity.hostInstanceId(), identity.runEpoch(),
                batch.getRunLeaseUntil() == null ? null : batch.getRunLeaseUntil().toInstant(ZoneOffset.UTC),
                batch.getDeadlineAt() == null ? null : batch.getDeadlineAt().toInstant(ZoneOffset.UTC),
                batch.getMaxAttempts(), batch.getMaxSlots());
    }

    /** 项目锁与批次锁由调用方持有；回执重放不消耗新尝试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasAttemptBudget(BatchContext context) {
        return context.maxAttempts() != null && batches.countAttempts(context.batchId()) < context.maxAttempts();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasExecutionSlot(BatchContext context) {
        return context.maxSlots() != null && batches.countUnconfirmedAttempts(context.batchId()) < context.maxSlots();
    }

    private TokenIdentity parse(String token, boolean allowExpired) {
        return parse(token, allowExpired, PURPOSE);
    }

    private TokenIdentity parse(String token, boolean allowExpired, String purpose) {
        try {
            Claims claims;
            try {
                claims = Jwts.parser().verifyWith(signingKey()).requireSubject(SUBJECT).requireIssuer(ISSUER)
                        .requireAudience(AUDIENCE)
                        .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token).getPayload();
            } catch (ExpiredJwtException expired) {
                if (!allowExpired) throw expired;
                claims = expired.getClaims();
            }
            if (!SUBJECT.equals(claims.getSubject()) || !ISSUER.equals(claims.getIssuer())
                    || claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)
                    || claims.getExpiration() == null)
                throw new IllegalArgumentException();
            if (!purpose.equals(claims.get("purpose", String.class)))
                throw new ForbiddenOperationException("批次凭据与当前宿主协议不匹配");
            String batchId = claims.get("batchId", String.class);
            String deviceId = claims.get("deviceId", String.class);
            String hostInstanceId = claims.get("hostInstanceId", String.class);
            if (batchId == null || batchId.isBlank() || deviceId == null || deviceId.isBlank()
                    || hostInstanceId == null || hostInstanceId.isBlank()) throw new IllegalArgumentException();
            return new TokenIdentity(batchId, number(claims.get("userId")), number(claims.get("projectId")),
                    number(claims.get("bindingId")), number(claims.get("bindingRevision")), deviceId,
                    number(claims.get("hostAuthorizationId")), hostInstanceId, number(claims.get("runEpoch")),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            throw denied();
        }
    }

    private Long number(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
    private ForbiddenOperationException denied() { return new ForbiddenOperationException(INVALID_BATCH); }

    private record TokenIdentity(String batchId, Long userId, Long projectId, Long bindingId, Long bindingRevision,
                                 String deviceId, Long hostAuthorizationId, String hostInstanceId, Long runEpoch,
                                 Instant expiresAt) { }
    public record IssuedBatch(String batchId, String batchToken, Instant expiresAt, Instant runLeaseUntil,
                              Instant deadlineAt, Long runEpoch) {
        @Override public String toString() {
            return "IssuedBatch[batchId=" + batchId + ", batchToken=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }
    public record BatchContext(String batchId, Long userId, Long projectId, Long bindingId, Long bindingRevision,
                               String deviceId, List<Long> taskIds, Instant expiresAt, Long hostAuthorizationId,
                               String hostInstanceId, Long runEpoch, Instant runLeaseUntil, Instant deadlineAt,
                               Integer maxAttempts, Integer maxSlots) { }
}
