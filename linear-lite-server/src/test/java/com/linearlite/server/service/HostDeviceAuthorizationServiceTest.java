package com.linearlite.server.service;

import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.HostDeviceAuthorizationMapper;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostDeviceAuthorizationServiceTest {
    private static final String DEVICE = "a1111111-2222-4333-8444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private HostDeviceAuthorizationMapper mapper;
    private CollaborationBatchMapper batches;
    private ProjectMapper projects;
    private HostDeviceAuthorizationService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "host-test"),
                HostDeviceAuthorization.class);
        mapper = mock(HostDeviceAuthorizationMapper.class);
        batches = mock(CollaborationBatchMapper.class);
        projects = mock(ProjectMapper.class);
        service = new HostDeviceAuthorizationService(mapper, batches, projects, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void springCanChooseTheInjectedConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HostDeviceAuthorizationMapper.class, () -> mapper);
            context.registerBean(CollaborationBatchMapper.class, () -> batches);
            context.registerBean(ProjectMapper.class, () -> projects);
            context.register(HostDeviceAuthorizationService.class);
            context.refresh();
            assertNotNull(context.getBean(HostDeviceAuthorizationService.class));
        }
    }

    @Test
    void newAuthorizationRevokesOldGrantButHasNoHeartbeat() {
        when(mapper.lockUser(7L)).thenReturn(7L);
        HostDeviceAuthorization grant = service.register("oauth-one", 7L, DEVICE, "Mac");
        assertFalse(service.isOnline(grant));
        assertNull(grant.getLastHeartbeatAt());
        assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), grant.getCreatedAt());
        verify(mapper).revokeActive(eq(7L), eq(DEVICE), eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        verify(mapper).insert(grant);
    }

    @Test
    void authorizationIsIdempotentAndCannotBeReusedByAnotherUser() {
        HostDeviceAuthorization grant = grant(7L, "oauth-one");
        when(mapper.lockUser(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.selectAuthorizationForUpdate("oauth-one")).thenReturn(grant);
        assertSame(grant, service.register("oauth-one", 7L, DEVICE, "Mac"));
        assertThrows(ForbiddenOperationException.class, () -> service.register("oauth-one", 8L, DEVICE, "Mac"));
        verify(mapper, never()).insert(any(HostDeviceAuthorization.class));
        grant.setRevokedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThrows(ForbiddenOperationException.class, () -> service.register("oauth-one", 7L, DEVICE, "Mac"));
    }

    @Test
    void onlineUsesServerUtcHeartbeatAndExactDeviceIdentity() {
        HostDeviceAuthorization grant = grant(7L, "oauth-one");
        when(mapper.selectActiveForUpdate(7L, DEVICE)).thenReturn(grant);
        assertThrows(ConflictOperationException.class, () -> service.requireOnlineGrant(7L, DEVICE));
        grant.setLastHeartbeatAt(LocalDateTime.ofInstant(NOW.minusSeconds(90), ZoneOffset.UTC));
        assertSame(grant, service.requireOnlineGrant(7L, DEVICE));
        grant.setLastHeartbeatAt(LocalDateTime.ofInstant(NOW.minusSeconds(91), ZoneOffset.UTC));
        assertThrows(ConflictOperationException.class, () -> service.requireOnlineGrant(7L, DEVICE));
        grant.setDeviceId(DEVICE.toUpperCase());
        assertThrows(ForbiddenOperationException.class, () -> service.requireOnlineGrant(7L, DEVICE));
    }

    @Test
    void heartbeatRechecksRevocationAndOwnedReadsDoNotCrossUsers() {
        HostDeviceAuthorization grant = grant(7L, "oauth-one");
        when(mapper.selectAuthorizationForUpdate("oauth-one")).thenReturn(grant);
        when(mapper.update(isNull(), any())).thenReturn(1);
        assertTrue(service.heartbeat("oauth-one", 7L).online());
        assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), grant.getLastHeartbeatAt());
        grant.setRevokedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThrows(ForbiddenOperationException.class, () -> service.heartbeat("oauth-one", 7L));
        when(mapper.selectById(1L)).thenReturn(grant);
        assertThrows(ResourceNotFoundException.class, () -> service.findByIdForUser(8L, 1L));
        assertThrows(ResourceNotFoundException.class, () -> service.revoke(8L, 1L));
    }

    @Test
    void authorizationLookupRejectsCaseInsensitiveDatabaseMatch() {
        HostDeviceAuthorization grant = grant(7L, "abc");
        when(mapper.selectByAuthorization("ABC")).thenReturn(grant);
        assertNull(service.findByAuthorization("ABC"));
        when(mapper.selectAuthorizationForUpdate("ABC")).thenReturn(grant);
        assertThrows(ForbiddenOperationException.class, () -> service.requireActiveAuthorization("ABC", 7L));
    }

    @Test
    void revocationStopsOnlyOriginalGrantProjectsInLockOrder() {
        HostDeviceAuthorization grant = grant(7L, "oauth-one");
        when(mapper.selectOwnedForUpdate(7L, 1L)).thenReturn(grant);
        when(batches.selectRunningProjectsForGrant(1L)).thenReturn(java.util.List.of(3L, 6L));
        when(projects.lockProject(3L)).thenReturn(3L);
        when(projects.lockProject(6L)).thenReturn(6L);
        service.revoke(7L, 1L);
        var order = inOrder(mapper, batches, projects);
        order.verify(mapper).selectOwnedForUpdate(7L, 1L);
        order.verify(mapper).update(isNull(), any());
        order.verify(batches).selectRunningProjectsForGrant(1L);
        order.verify(projects).lockProject(3L);
        order.verify(batches).requestStopForGrant(eq(3L), eq(1L), any());
        order.verify(projects).lockProject(6L);
        order.verify(batches).requestStopForGrant(eq(6L), eq(1L), any());
    }

    @Test
    void reauthorizationStopsPreviousGrantBatchesBeforeReturningNewGrant() {
        HostDeviceAuthorization previous = grant(7L, "old-auth");
        when(mapper.lockUser(7L)).thenReturn(7L);
        when(mapper.selectActiveForUpdate(7L, DEVICE)).thenReturn(previous);
        when(batches.selectRunningProjectsForGrant(1L)).thenReturn(java.util.List.of(6L));
        when(projects.lockProject(6L)).thenReturn(6L);
        service.register("new-auth", 7L, DEVICE, "Mac");
        var order = inOrder(mapper, batches, projects);
        order.verify(mapper).selectActiveForUpdate(7L, DEVICE);
        order.verify(mapper).revokeActive(eq(7L), eq(DEVICE), any());
        order.verify(batches).selectRunningProjectsForGrant(1L);
        order.verify(projects).lockProject(6L);
        order.verify(batches).requestStopForGrant(eq(6L), eq(1L), any());
        order.verify(mapper).insert(any(HostDeviceAuthorization.class));
    }

    @Test
    void responseSerializesUtcTimesWithExplicitZone() throws Exception {
        HostDeviceAuthorization grant = grant(7L, "oauth-one");
        grant.setCreatedAt(LocalDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC));
        grant.setLastHeartbeatAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        String json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(service.response(grant));
        assertTrue(json.contains("2026-11-10T08:00:00Z"));
        assertTrue(json.contains("2026-11-10T07:59:30Z"));
    }

    private static HostDeviceAuthorization grant(long userId, String authorizationId) {
        HostDeviceAuthorization grant = new HostDeviceAuthorization();
        grant.setId(1L);
        grant.setUserId(userId);
        grant.setDeviceId(DEVICE);
        grant.setDeviceName("Mac");
        grant.setOauthAuthorizationId(authorizationId);
        return grant;
    }
}
