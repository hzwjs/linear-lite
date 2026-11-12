package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linearlite.server.dto.HostDeviceAuthorizationResponse;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.HostDeviceAuthorizationMapper;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** 一次 OAuth 授权对应一条不可复用的设备授权记录。 */
@Service
public class HostDeviceAuthorizationService {
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);
    private final HostDeviceAuthorizationMapper mapper;
    private final CollaborationBatchMapper batches;
    private final ProjectMapper projects;
    private final Clock clock;

    @Autowired
    public HostDeviceAuthorizationService(HostDeviceAuthorizationMapper mapper,
                                          CollaborationBatchMapper batches, ProjectMapper projects) {
        this(mapper, batches, projects, Clock.systemUTC());
    }

    HostDeviceAuthorizationService(HostDeviceAuthorizationMapper mapper, CollaborationBatchMapper batches,
                                   ProjectMapper projects, Clock clock) {
        this.mapper = mapper;
        this.batches = batches;
        this.projects = projects;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public HostDeviceAuthorization register(String authorizationId, Long userId, String deviceId, String deviceName) {
        requireIdentity(authorizationId, userId, deviceId, deviceName);
        if (mapper.lockUser(userId) == null) throw new ForbiddenOperationException("宿主账号不存在");
        HostDeviceAuthorization existing = mapper.selectAuthorizationForUpdate(authorizationId);
        if (existing != null) {
            if (!Objects.equals(existing.getOauthAuthorizationId(), authorizationId)
                    || !Objects.equals(existing.getUserId(), userId) || !Objects.equals(existing.getDeviceId(), deviceId)
                    || existing.getRevokedAt() != null) {
                throw new ForbiddenOperationException("宿主授权无效");
            }
            return existing;
        }
        LocalDateTime now = now();
        HostDeviceAuthorization previous = mapper.selectActiveForUpdate(userId, deviceId);
        mapper.revokeActive(userId, deviceId, now);
        if (previous != null) stopGrantBatches(previous.getId(), now);
        HostDeviceAuthorization created = new HostDeviceAuthorization();
        created.setUserId(userId);
        created.setDeviceId(deviceId);
        created.setDeviceName(deviceName);
        created.setOauthAuthorizationId(authorizationId);
        created.setCreatedAt(now);
        mapper.insert(created);
        return created;
    }

    /** 先锁授权，再锁项目；调用方事务贯穿批次创建。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public HostDeviceAuthorization requireOnlineGrant(Long userId, String deviceId) {
        HostDeviceAuthorization grant = mapper.selectActiveForUpdate(userId, deviceId);
        if (grant == null || !Objects.equals(grant.getDeviceId(), deviceId)) {
            throw new ForbiddenOperationException("宿主设备未授权");
        }
        if (!isOnline(grant)) throw new ConflictOperationException("宿主设备离线");
        return grant;
    }

    public HostDeviceAuthorization findByIdForUser(Long userId, Long grantId) {
        HostDeviceAuthorization grant = mapper.selectById(grantId);
        if (grant == null || !Objects.equals(grant.getUserId(), userId)) {
            throw new ResourceNotFoundException("宿主设备授权不存在");
        }
        return grant;
    }

    public HostDeviceAuthorization findByAuthorization(String authorizationId) {
        HostDeviceAuthorization grant = mapper.selectByAuthorization(authorizationId);
        return grant != null && Objects.equals(grant.getOauthAuthorizationId(), authorizationId) ? grant : null;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public HostDeviceAuthorization requireActiveAuthorization(String authorizationId, Long userId) {
        HostDeviceAuthorization grant = mapper.selectAuthorizationForUpdate(authorizationId);
        if (grant == null || !Objects.equals(grant.getOauthAuthorizationId(), authorizationId)
                || grant.getRevokedAt() != null || !Objects.equals(grant.getUserId(), userId)) {
            throw new ForbiddenOperationException("宿主设备授权已撤销");
        }
        return grant;
    }

    @Transactional(rollbackFor = Exception.class)
    public HostDeviceAuthorizationResponse heartbeat(String authorizationId, Long userId) {
        HostDeviceAuthorization grant = requireActiveAuthorization(authorizationId, userId);
        LocalDateTime now = now();
        int updated = mapper.update(null, new LambdaUpdateWrapper<HostDeviceAuthorization>()
                .eq(HostDeviceAuthorization::getId, grant.getId())
                .isNull(HostDeviceAuthorization::getRevokedAt)
                .set(HostDeviceAuthorization::getLastHeartbeatAt, now));
        if (updated != 1) throw new ForbiddenOperationException("宿主设备授权已撤销");
        grant.setLastHeartbeatAt(now);
        return response(grant);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long userId, Long grantId) {
        HostDeviceAuthorization grant = mapper.selectOwnedForUpdate(userId, grantId);
        if (grant == null) throw new ResourceNotFoundException("宿主设备授权不存在");
        if (grant.getRevokedAt() == null) {
            mapper.update(null, new LambdaUpdateWrapper<HostDeviceAuthorization>()
                    .eq(HostDeviceAuthorization::getId, grantId)
                    .isNull(HostDeviceAuthorization::getRevokedAt)
                    .set(HostDeviceAuthorization::getRevokedAt, now()));
            stopGrantBatches(grantId, now());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void revokeAuthorization(String authorizationId) {
        HostDeviceAuthorization grant = mapper.selectAuthorizationForUpdate(authorizationId);
        if (grant != null && Objects.equals(grant.getOauthAuthorizationId(), authorizationId)
                && grant.getRevokedAt() == null) {
            mapper.update(null, new LambdaUpdateWrapper<HostDeviceAuthorization>()
                    .eq(HostDeviceAuthorization::getId, grant.getId())
                    .isNull(HostDeviceAuthorization::getRevokedAt)
                    .set(HostDeviceAuthorization::getRevokedAt, now()));
            stopGrantBatches(grant.getId(), now());
        }
    }

    public List<HostDeviceAuthorizationResponse> list(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<HostDeviceAuthorization>()
                .eq(HostDeviceAuthorization::getUserId, userId)
                .orderByDesc(HostDeviceAuthorization::getId)).stream().map(this::response).toList();
    }

    public HostDeviceAuthorizationResponse response(HostDeviceAuthorization grant) {
        return new HostDeviceAuthorizationResponse(grant.getId(), grant.getUserId(), grant.getDeviceId(),
                grant.getDeviceName(), grant.getRevokedAt() == null, isOnline(grant),
                grant.getLastHeartbeatAt() == null ? null : grant.getLastHeartbeatAt().toInstant(ZoneOffset.UTC),
                grant.getCreatedAt() == null ? null : grant.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    /** 只读判断：该用户该设备是否有未撤销且心跳在时限内的授权。 */
    public boolean isOnlineDevice(Long userId, String deviceId) {
        HostDeviceAuthorization grant = mapper.selectActive(userId, deviceId);
        return grant != null && isOnline(grant);
    }

    public boolean isOnline(HostDeviceAuthorization grant) {
        LocalDateTime heartbeat = grant.getLastHeartbeatAt();
        return grant.getRevokedAt() == null && heartbeat != null
                && !heartbeat.isBefore(now().minus(HEARTBEAT_TIMEOUT));
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    /** 授权行已锁定；按项目编号顺序提交该授权的批次停止意图。 */
    private void stopGrantBatches(Long grantId, LocalDateTime occurredAt) {
        for (Long projectId : batches.selectRunningProjectsForGrant(grantId)) {
            if (projects.lockProject(projectId) == null) throw new IllegalStateException("批次所属项目不存在");
            batches.requestStopForGrant(projectId, grantId, occurredAt);
        }
    }

    private static void requireIdentity(String authorizationId, Long userId, String deviceId, String deviceName) {
        if (authorizationId == null || authorizationId.isBlank() || authorizationId.length() > 100
                || userId == null || userId <= 0 || deviceId == null || deviceId.isBlank()
                || !deviceId.equals(deviceId.trim()) || deviceId.length() > 128
                || deviceName == null || deviceName.isBlank() || deviceName.length() > 128) {
            throw new IllegalArgumentException("宿主设备身份无效");
        }
    }
}
