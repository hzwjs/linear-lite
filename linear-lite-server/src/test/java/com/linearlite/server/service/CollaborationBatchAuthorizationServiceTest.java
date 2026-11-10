package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaborationBatchAuthorizationServiceTest {
    @Mock private ProjectDeviceBindingMapper bindings;
    @Mock private TaskExecutionAttemptMapper attempts;
    @Mock private ProjectMapper projects;
    @Mock private ProjectAccessGuard access;
    @Mock private TaskMapper tasks;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
    private JwtProperties jwt;
    private ProjectDeviceBindingService bindingService;
    private CollaborationBatchAuthorizationService service;
    private ProjectDeviceBinding binding;

    @BeforeEach
    void setUp() {
        jwt = new JwtProperties();
        jwt.setSecret(UUID.randomUUID().toString() + UUID.randomUUID());
        bindingService = new ProjectDeviceBindingService(bindings, attempts, projects, access);
        service = new CollaborationBatchAuthorizationService(bindingService, tasks, jwt, clock);
        binding = new ProjectDeviceBinding();
        binding.setId(10L);
        binding.setUserId(1L);
        binding.setProjectId(6L);
        binding.setDeviceId("device-A");
        binding.setProjectDirectory("/work/a");
        binding.setRevision(1L);
    }

    @Test
    void authorizesFixedBindingTaskScopeAndExpiry() {
        stubValidIssuance();
        when(tasks.selectById(100L)).thenReturn(task(100L, 6L));
        Instant expiry = clock.instant().plusSeconds(300).plusNanos(100);
        var issued = service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L), expiry);

        var context = service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L);
        var observed = service.observeBatch(issued.batchToken(), 1L, 10L, "device-A", "/work/a");
        assertEquals(issued.batchId(), context.batchId());
        assertEquals(issued.batchId(), observed.batchId());
        assertEquals(1L, context.userId());
        assertEquals(6L, context.projectId());
        assertEquals(10L, context.bindingId());
        assertEquals(1L, context.bindingRevision());
        assertEquals(List.of(100L, 101L), context.taskIds());
        assertEquals(Instant.ofEpochSecond(expiry.getEpochSecond()), issued.expiresAt());
        assertEquals(issued.expiresAt(), context.expiresAt());
        assertFalse(issued.toString().contains(issued.batchToken()));
        assertTrue(issued.toString().contains("[REDACTED]"));
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", null));
    }

    @Test
    void rejectsTaskWhenItIsMissingOrNoLongerBelongsToTheProject() {
        stubValidIssuance();
        var issued = service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                clock.instant().plusSeconds(300));
        when(tasks.selectById(100L)).thenReturn(null, task(100L, 7L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
    }

    @Test
    void rejectsOtherUserBindingDeviceDirectoryAndTask() {
        stubValidIssuance();
        var issued = service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                clock.instant().plusSeconds(300));

        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 2L, 10L, "device-A", "/work/a", 100L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 11L, "device-A", "/work/a", 100L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-B", "/work/a", 100L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/b", 100L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 102L));
    }

    @Test
    void rejectsExpiredOrAlteredBatchWithSanitizedError() {
        stubValidIssuance();
        var issued = service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                clock.instant().plusSeconds(300));
        var afterExpiry = new CollaborationBatchAuthorizationService(bindingService, tasks, jwt,
                Clock.offset(clock, Duration.ofMinutes(6)));

        var expired = assertThrows(ForbiddenOperationException.class,
                () -> afterExpiry.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
        assertEquals("批次授权无效或过期", expired.getMessage());
        assertFalse(expired.getMessage().contains(issued.batchToken()));
        assertEquals(6L, afterExpiry.recoveryContext(issued.batchToken(), 1L).projectId());
        assertThrows(ForbiddenOperationException.class, () -> afterExpiry.recoveryContext(issued.batchToken(), 2L));

        int signatureStart = issued.batchToken().lastIndexOf('.') + 1;
        char first = issued.batchToken().charAt(signatureStart);
        String altered = issued.batchToken().substring(0, signatureStart) + (first == 'A' ? 'B' : 'A')
                + issued.batchToken().substring(signatureStart + 1);
        var rejected = assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(altered, 1L, 10L, "device-A", "/work/a", 100L));
        assertEquals("批次授权无效或过期", rejected.getMessage());
        assertFalse(rejected.getMessage().contains(altered));
        assertThrows(ForbiddenOperationException.class, () -> afterExpiry.recoveryContext(altered, 1L));
    }

    @Test
    void expiredRecoveryStillRejectsWrongPurposeAndAudience() {
        String token = io.jsonwebtoken.Jwts.builder().subject("wrong").issuer("wrong")
                .audience().add("wrong").and().claim("purpose", "wrong")
                .expiration(java.util.Date.from(clock.instant().minusSeconds(10)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(jwt.getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        assertThrows(ForbiddenOperationException.class, () -> service.recoveryContext(token, 1L));
    }

    @Test
    void bindingChangeRoundTripAndDeletionCannotReviveOldBatch() {
        stubValidIssuance();
        var issued = service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                clock.instant().plusSeconds(300));

        binding.setProjectDirectory("/work/b");
        binding.setRevision(2L);
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/b", 100L));
        binding.setProjectDirectory("/work/a");
        binding.setRevision(3L);
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
        binding.setDeletedAt(java.time.LocalDateTime.of(2026, 11, 10, 10, 0));
        binding.setRevision(4L);
        assertThrows(ResourceNotFoundException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
        binding.setDeletedAt(null);
        binding.setRevision(5L);
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
    }

    @Test
    void membershipRevocationRejectsAnIssuedBatch() {
        stubValidIssuance();
        var issued = service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                clock.instant().plusSeconds(300));
        doThrow(new ForbiddenOperationException("非项目成员")).when(access).requireMember(6L, 1L);
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(issued.batchToken(), 1L, 10L, "device-A", "/work/a", 100L));
    }

    @Test
    void rejectsTasksOutsideBoundProjectAndInvalidScopes() {
        assertThrows(IllegalArgumentException.class,
                () -> service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 100L),
                        clock.instant().plusSeconds(300)));
        assertThrows(IllegalArgumentException.class,
                () -> service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(),
                        clock.instant().plusSeconds(300)));
        assertThrows(IllegalArgumentException.class,
                () -> service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L), clock.instant()));

        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(binding);
        when(tasks.selectBatchIds(List.of(100L, 101L))).thenReturn(List.of(task(100L, 6L), task(101L, 7L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                        clock.instant().plusSeconds(300)));
    }

    @Test
    void batchAndLoginTokensAreNotInterchangeable() throws Exception {
        stubValidIssuance();
        var liveService = new CollaborationBatchAuthorizationService(bindingService, tasks, jwt, Clock.systemUTC());
        var issued = liveService.issue(1L, 6L, 10L, "device-A", "/work/a", List.of(100L, 101L),
                Instant.now().plusSeconds(300));
        JwtUtil login = new JwtUtil(jwt);
        assertThrows(NumberFormatException.class, () -> login.getUserIdFromClaims(login.parseToken(issued.batchToken())));

        @SuppressWarnings("unchecked")
        ObjectProvider<OAuth2AuthorizationService> provider = mock(ObjectProvider.class);
        JwtAuthFilter filter = new JwtAuthFilter(login, new ObjectMapper(), new McpOAuthProperties(), provider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("Authorization", "Bearer " + issued.batchToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().contains(issued.batchToken()));

        String loginToken = Jwts.builder().subject("1").issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
        var rejected = assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(loginToken, 1L, 10L, "device-A", "/work/a", 100L));
        assertEquals("批次授权无效或过期", rejected.getMessage());
    }

    private void stubValidIssuance() {
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device-A")).thenReturn(binding);
        when(tasks.selectBatchIds(List.of(100L, 101L))).thenReturn(List.of(task(100L, 6L), task(101L, 6L)));
    }

    private static Task task(Long id, Long projectId) {
        Task task = new Task();
        task.setId(id);
        task.setProjectId(projectId);
        return task;
    }
}
