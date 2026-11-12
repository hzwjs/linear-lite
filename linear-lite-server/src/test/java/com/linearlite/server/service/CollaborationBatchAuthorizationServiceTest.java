package com.linearlite.server.service;

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
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollaborationBatchAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private final ProjectDeviceBindingService bindingService = mock(ProjectDeviceBindingService.class);
    private final HostDeviceAuthorizationMapper hosts = mock(HostDeviceAuthorizationMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    private final CollaborationBatchMapper batches = mock(CollaborationBatchMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final JwtProperties jwt = new JwtProperties();
    private CollaborationBatchAuthorizationService service;
    private CollaborationBatch batch;
    private ProjectDeviceBinding binding;
    private HostDeviceAuthorization grant;

    @BeforeEach void setup() {
        jwt.setSecret("unit-test-persisted-batch-signing-key-32-bytes");
        service = serviceAt(NOW);
        batch = new CollaborationBatch();
        batch.setBatchId("persisted-batch"); batch.setUserId(1L); batch.setProjectId(6L);
        batch.setBindingId(30L); batch.setBindingRevision(2L); batch.setHostAuthorizationId(9L);
        batch.setHostInstanceId("host-1"); batch.setRunEpoch(4L); batch.setLifecycle("accepted");
        batch.setDesiredIntent("RUN"); batch.setMaxAttempts(5); batch.setMaxSlots(2);
        batch.setRunLeaseUntil(LocalDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC));
        batch.setDeadlineAt(LocalDateTime.ofInstant(NOW.plusSeconds(600), ZoneOffset.UTC));
        binding = new ProjectDeviceBinding(); binding.setId(30L); binding.setUserId(1L); binding.setProjectId(6L);
        binding.setDeviceId("device"); binding.setProjectDirectory("/work"); binding.setRevision(2L);
        grant = new HostDeviceAuthorization(); grant.setId(9L); grant.setUserId(1L); grant.setDeviceId("device");
        when(hosts.selectOwnedForUpdate(1L, 9L)).thenReturn(grant);
        when(bindingService.requireForBatch(1L, 6L, 30L, "device", "/work")).thenReturn(binding);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(1L, 6L, "device")).thenReturn(binding);
        when(batches.selectForUpdate("persisted-batch")).thenReturn(batch);
        when(batches.selectTaskIds("persisted-batch")).thenReturn(List.of(100L, 101L));
        Task task = new Task(); task.setId(100L); task.setProjectId(6L);
        when(tasks.selectById(100L)).thenReturn(task);
    }

    @Test void acceptedPersistedBatchUsesFixedIdentityAndLocksBeforeTaskUse() {
        var issued = service.issueAccepted(batch, binding, grant);
        assertEquals("persisted-batch", issued.batchId());
        assertEquals(4L, issued.runEpoch());
        assertFalse(issued.toString().contains(issued.batchToken()));
        var context = service.authorizeTask(issued.batchToken(), 1L, 30L, "device", "/work", 100L);
        assertEquals(List.of(100L, 101L), context.taskIds());
        assertEquals("host-1", context.hostInstanceId());
        assertEquals(9L, context.hostAuthorizationId());
        assertEquals(batch.getRunLeaseUntil().toInstant(ZoneOffset.UTC), context.runLeaseUntil());
        var order = inOrder(hosts, bindingService, batches, tasks);
        order.verify(hosts).selectOwnedForUpdate(1L, 9L);
        order.verify(bindingService).requireForBatch(1L, 6L, 30L, "device", "/work");
        order.verify(batches).selectForUpdate("persisted-batch");
        order.verify(batches).selectTaskIds("persisted-batch");
        order.verify(tasks).selectById(100L);
    }

    @Test void stopGrantRevocationAndExpiredRunLeaseForbidBusinessButPreserveRecovery() {
        String token = service.issueAccepted(batch, binding, grant).batchToken();
        batch.setDesiredIntent("STOP");
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(token, 1L, 30L, "device", "/work", 100L));
        assertEquals("persisted-batch", service.recoveryContext(token, 1L).batchId());
        batch.setDesiredIntent("RUN"); grant.setRevokedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(token, 1L, 30L, "device", "/work", 100L));
        assertEquals(9L, service.recoveryContext(token, 1L).hostAuthorizationId());
        grant.setRevokedAt(null);
        batch.setRunLeaseUntil(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(token, 1L, 30L, "device", "/work", 100L));
        assertEquals("host-1", service.recoveryContext(token, 1L).hostInstanceId());
    }

    @Test void wrongUserGrantDeviceEpochAndTaskAreRejected() {
        String token = service.issueAccepted(batch, binding, grant).batchToken();
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(token, 2L, 30L, "device", "/work", 100L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireHostIdentity(token, 1L, 10L, "device"));
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireHostIdentity(token, 1L, 9L, "another-device"));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(token, 1L, 30L, "device", "/work", 102L));
        batch.setRunEpoch(5L);
        assertThrows(ForbiddenOperationException.class, () -> service.recoveryContext(token, 1L));
        assertThrows(ForbiddenOperationException.class,
                () -> service.authorizeTask(token, 1L, 30L, "device", "/work", 100L));
    }

    @Test void expiredOriginalTokenCanOnlyRecoverAndSignatureAndPurposeRemainMandatory() {
        String token = service.issueAccepted(batch, binding, grant).batchToken();
        var expired = serviceAt(NOW.plusSeconds(601));
        assertThrows(ForbiddenOperationException.class,
                () -> expired.authorizeTask(token, 1L, 30L, "device", "/work", 100L));
        assertEquals("persisted-batch", expired.recoveryContext(token, 1L).batchId());
        assertThrows(ForbiddenOperationException.class, () -> expired.recoveryContext(token, 2L));
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);
        assertThrows(ForbiddenOperationException.class,
                () -> expired.recoveryContext(String.join(".", parts), 1L));
        String oldStateless = Jwts.builder().subject("collaboration-batch").issuer("linear-lite-collaboration")
                .audience().add("linear-lite-collaboration-batch").and().claim("purpose", "stigmergy-batch")
                .claim("batchId", "persisted-batch").claim("userId", 1L).claim("projectId", 6L)
                .claim("bindingId", 30L).claim("bindingRevision", 2L).claim("deviceId", "device")
                .expiration(Date.from(NOW.plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
        assertThrows(ForbiddenOperationException.class, () -> expired.recoveryContext(oldStateless, 1L));
    }

    @Test void issuedCredentialsCarryTheCurrentProtocolPurposes() {
        String active = service.issueAccepted(batch, binding, grant).batchToken();
        var queued = new HostCollaborationBatchService.Context("persisted-batch", 6L, 30L, 2L, "device", "/work",
                List.of(100L), 2, 60000L, 5, 0L, "queued", "RUN", null, 3L, null, null);
        String prepared = service.prepareAcceptance(queued, 1L, 9L, "host-1").recoveryToken();
        var parser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(NOW)).build();
        assertEquals("stigmergy-batch-v3", parser.parseSignedClaims(active).getPayload().get("purpose", String.class));
        assertEquals("stigmergy-accept-recovery-v3", parser.parseSignedClaims(prepared).getPayload().get("purpose", String.class));
    }

    @Test void previousProtocolTokensCannotAuthorizeOrRecoverEvenWithValidSignatures() {
        for (Instant expiry : List.of(NOW.plusSeconds(300), NOW.minusSeconds(300))) {
            String oldBatch = signedPurpose("stigmergy-batch", expiry);
            assertThrows(ForbiddenOperationException.class, () -> service.requireHostIdentity(oldBatch, 1L, 9L, "device"));
            assertThrows(ForbiddenOperationException.class, () -> service.recoveryUserId(oldBatch));
            assertThrows(ForbiddenOperationException.class, () -> service.recoveryContext(oldBatch, 1L));
            assertThrows(ForbiddenOperationException.class,
                    () -> service.authorizeTask(oldBatch, 1L, 30L, "device", "/work", 100L));
            String oldPrepared = signedPurpose("stigmergy-accept-recovery", expiry);
            assertThrows(ForbiddenOperationException.class, () -> service.lockPreparedAcceptance(oldPrepared));
            assertThrows(ForbiddenOperationException.class,
                    () -> service.verifyPreparedAcceptance(oldPrepared, batch, "device", "host-1"));
        }
    }

    @Test void issuanceRequiresAcceptedCurrentBindingAndLiveOriginalGrant() {
        batch.setLifecycle("queued");
        assertThrows(ForbiddenOperationException.class, () -> service.issueAccepted(batch, binding, grant));
        batch.setLifecycle("accepted"); binding.setRevision(3L);
        assertThrows(ForbiddenOperationException.class, () -> service.issueAccepted(batch, binding, grant));
        binding.setRevision(2L); grant.setRevokedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThrows(ForbiddenOperationException.class, () -> service.issueAccepted(batch, binding, grant));
    }

    @Test void slotsAndAttemptsUseCommittedBatchCounts() {
        var context = service.recoveryContext(service.issueAccepted(batch, binding, grant).batchToken(), 1L);
        when(batches.countAttempts("persisted-batch")).thenReturn(4L, 5L);
        when(batches.countUnconfirmedAttempts("persisted-batch")).thenReturn(1L, 2L);
        assertTrue(service.hasAttemptBudget(context)); assertFalse(service.hasAttemptBudget(context));
        assertTrue(service.hasExecutionSlot(context)); assertFalse(service.hasExecutionSlot(context));
    }

    @Test void preparedRecoveryMatchesOnlyTheOriginalHostAndEpochAfterRevocation() {
        var queued = new HostCollaborationBatchService.Context("persisted-batch", 6L, 30L, 2L, "device", "/work",
                List.of(100L), 2, 60000L, 5, 0L, "queued", "RUN", null, 3L, null, null);
        var prepared = service.prepareAcceptance(queued, 1L, 9L, "host-1");
        assertEquals(4L, prepared.runEpoch());
        assertFalse(prepared.toString().contains(prepared.recoveryToken()));
        assertThrows(ForbiddenOperationException.class,
                () -> service.requireHostIdentity(prepared.recoveryToken(), 1L, 9L, "device"));
        assertThrows(ForbiddenOperationException.class, () -> service.recoveryUserId(prepared.recoveryToken()));

        batch.setLifecycle("queued"); batch.setHostInstanceId(null); batch.setRunEpoch(3L);
        assertSame(batch, service.lockPreparedAcceptance(prepared.recoveryToken()));
        batch.setLifecycle("accepted"); batch.setHostInstanceId("host-1"); batch.setRunEpoch(4L);
        assertDoesNotThrow(() -> service.verifyPreparedAcceptance(prepared.recoveryToken(), batch, "device", "host-1"));
        assertThrows(ForbiddenOperationException.class,
                () -> service.verifyPreparedAcceptance(prepared.recoveryToken(), batch, "device", "host-2"));
        grant.setRevokedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        var later = serviceAt(NOW.plusSeconds(600));
        assertSame(batch, later.lockPreparedAcceptance(prepared.recoveryToken()));
        assertThrows(ForbiddenOperationException.class,
                () -> later.verifyPreparedAcceptance(prepared.recoveryToken(), batch, "device", "host-1"));
        batch.setHostInstanceId("host-2");
        assertThrows(ForbiddenOperationException.class, () -> later.lockPreparedAcceptance(prepared.recoveryToken()));
        batch.setHostInstanceId("host-1"); batch.setRunEpoch(5L);
        assertThrows(ForbiddenOperationException.class, () -> later.lockPreparedAcceptance(prepared.recoveryToken()));
    }

    private CollaborationBatchAuthorizationService serviceAt(Instant time) {
        return new CollaborationBatchAuthorizationService(bindingService, hosts, projects, bindings, batches,
                tasks, jwt, Clock.fixed(time, ZoneOffset.UTC));
    }

    private String signedPurpose(String purpose, Instant expiry) {
        return Jwts.builder().subject("collaboration-batch").issuer("linear-lite-collaboration")
                .audience().add("linear-lite-collaboration-batch").and().claim("purpose", purpose)
                .claim("batchId", "persisted-batch").claim("userId", 1L).claim("projectId", 6L)
                .claim("bindingId", 30L).claim("bindingRevision", 2L).claim("deviceId", "device")
                .claim("hostAuthorizationId", 9L).claim("hostInstanceId", "host-1").claim("runEpoch", 4L)
                .expiration(Date.from(expiry))
                .signWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
    }
}
