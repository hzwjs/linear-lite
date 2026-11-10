package com.linearlite.server.service;

import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.TaskMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 批次用途令牌；用户标识由调用方的认证上下文提供。 */
@Service
public class CollaborationBatchAuthorizationService {
    private static final String SUBJECT = "collaboration-batch";
    private static final String ISSUER = "linear-lite-collaboration";
    private static final String AUDIENCE = "linear-lite-collaboration-batch";
    private static final String PURPOSE = "stigmergy-batch";
    private static final String INVALID_BATCH = "批次授权无效或过期";

    private final ProjectDeviceBindingService bindingService;
    private final TaskMapper taskMapper;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    @Autowired
    public CollaborationBatchAuthorizationService(ProjectDeviceBindingService bindingService,
                                                   TaskMapper taskMapper, JwtProperties jwtProperties) {
        this(bindingService, taskMapper, jwtProperties, Clock.systemUTC());
    }

    CollaborationBatchAuthorizationService(ProjectDeviceBindingService bindingService,
                                           TaskMapper taskMapper, JwtProperties jwtProperties, Clock clock) {
        this.bindingService = bindingService;
        this.taskMapper = taskMapper;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public IssuedBatch issue(Long authenticatedUserId, Long projectId, Long bindingId,
                             String deviceId, String projectDirectory, List<Long> taskIds, Instant expiresAt) {
        requireTaskScope(taskIds);
        Instant now = clock.instant();
        if (expiresAt == null || expiresAt.getEpochSecond() <= now.getEpochSecond()) {
            throw new IllegalArgumentException("批次有效期须晚于签发时间");
        }
        Instant effectiveExpiry = Instant.ofEpochSecond(expiresAt.getEpochSecond());
        ProjectDeviceBinding binding = bindingService.requireForBatch(
                authenticatedUserId, projectId, bindingId, deviceId, projectDirectory);
        List<Task> tasks = taskMapper.selectBatchIds(taskIds);
        if (tasks.size() != taskIds.size() || tasks.stream().anyMatch(task -> !projectId.equals(task.getProjectId()))) {
            throw new IllegalArgumentException("批次任务须属于绑定项目");
        }
        String batchId = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(SUBJECT)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("purpose", PURPOSE)
                .claim("batchId", batchId)
                .claim("userId", authenticatedUserId)
                .claim("projectId", projectId)
                .claim("bindingId", bindingId)
                .claim("bindingRevision", binding.getRevision())
                .claim("deviceId", deviceId)
                .claim("taskIds", List.copyOf(taskIds))
                .issuedAt(Date.from(now))
                .expiration(Date.from(effectiveExpiry))
                .signWith(signingKey())
                .compact();
        return new IssuedBatch(batchId, token, effectiveExpiry);
    }

    /** 观察操作返回批次范围，任务操作使用 authorizeTask。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BatchContext observeBatch(String token, Long authenticatedUserId, Long expectedBindingId,
                                     String deviceId, String projectDirectory) {
        return authorize(token, authenticatedUserId, expectedBindingId, deviceId, projectDirectory, null);
    }

    /** 任务操作携带目标标识，调用方事务贯穿授权与业务写入。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BatchContext authorizeTask(String token, Long authenticatedUserId, Long expectedBindingId,
                                      String deviceId, String projectDirectory, Long taskId) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("任务标识无效");
        return authorize(token, authenticatedUserId, expectedBindingId, deviceId, projectDirectory, taskId);
    }

    private BatchContext authorize(String token, Long authenticatedUserId, Long expectedBindingId,
                                   String deviceId, String projectDirectory, Long taskId) {
        BatchContext context = parseContext(token);
        if (!Objects.equals(context.userId(), authenticatedUserId)
                || !Objects.equals(context.bindingId(), expectedBindingId)
                || !Objects.equals(context.deviceId(), deviceId)
                || (taskId != null && !context.taskIds().contains(taskId))) {
            throw new ForbiddenOperationException("批次授权与请求范围不匹配");
        }
        ProjectDeviceBinding binding = bindingService.requireForBatch(authenticatedUserId, context.projectId(),
                expectedBindingId, deviceId, projectDirectory);
        if (!Objects.equals(binding.getRevision(), context.bindingRevision())) {
            throw new ForbiddenOperationException("批次绑定版本已失效");
        }
        if (taskId != null) {
            Task task = taskMapper.selectById(taskId);
            if (task == null || !context.projectId().equals(task.getProjectId())) {
                throw new ForbiddenOperationException("批次任务与项目不匹配");
            }
        }
        return context;
    }

    private BatchContext parseContext(String token) {
        return parseContext(token, false);
    }

    /** 只供领取确认丢失后的停止恢复使用，不能授予业务写入权。 */
    public BatchContext recoveryContext(String token, Long authenticatedUserId) {
        BatchContext context = parseContext(token, true);
        if (!Objects.equals(context.userId(), authenticatedUserId))
            throw new ForbiddenOperationException(INVALID_BATCH);
        return context;
    }

    private BatchContext parseContext(String token, boolean allowExpired) {
        try {
            Claims claims;
            try { claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireSubject(SUBJECT)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .require("purpose", PURPOSE)
                    .clock(() -> Date.from(clock.instant()))
                    .build().parseSignedClaims(token).getPayload();
            } catch (io.jsonwebtoken.ExpiredJwtException expired) {
                if (!allowExpired) throw expired;
                claims = expired.getClaims();
            }
            // 过期异常可能早于 require 检查；恢复路径也须核验用途和固定受众。
            if (!SUBJECT.equals(claims.getSubject()) || !ISSUER.equals(claims.getIssuer())
                    || claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)
                    || !PURPOSE.equals(claims.get("purpose", String.class))) throw new IllegalArgumentException();
            String batchId = claims.get("batchId", String.class);
            String deviceId = claims.get("deviceId", String.class);
            Object rawTasks = claims.get("taskIds");
            if (batchId == null || batchId.isBlank() || deviceId == null || deviceId.isBlank()
                    || claims.getExpiration() == null || !(rawTasks instanceof List<?> items)) {
                throw new IllegalArgumentException();
            }
            List<Long> taskIds = new ArrayList<>();
            for (Object item : items) taskIds.add(numberValue(item));
            requireTaskScope(taskIds);
            return new BatchContext(batchId, numberValue(claims.get("userId")), numberValue(claims.get("projectId")),
                    numberValue(claims.get("bindingId")), numberValue(claims.get("bindingRevision")),
                    deviceId, List.copyOf(taskIds), claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            // 解析错误禁止携带令牌、claims 或底层异常信息。
            throw new ForbiddenOperationException(INVALID_BATCH);
        }
    }

    private Long numberValue(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
        long result = ((Number) value).longValue();
        if (result <= 0) throw new IllegalArgumentException();
        return result;
    }

    private void requireTaskScope(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty() || taskIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(taskIds).size() != taskIds.size()) {
            throw new IllegalArgumentException("批次任务范围无效");
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public record IssuedBatch(String batchId, String batchToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedBatch[batchId=" + batchId + ", batchToken=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }

    public record BatchContext(String batchId, Long userId, Long projectId, Long bindingId,
                               Long bindingRevision, String deviceId, List<Long> taskIds, Instant expiresAt) { }
}
