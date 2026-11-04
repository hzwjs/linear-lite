package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.config.RemoteTerminalProperties;
import com.linearlite.server.dto.RemoteTerminalDtos.Catalog;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.CertificateResponse;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.RtCaKey;
import com.linearlite.server.entity.RtCertificate;
import com.linearlite.server.entity.RtGrant;
import com.linearlite.server.entity.RtProfile;
import com.linearlite.server.entity.RtTarget;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.RtCaKeyMapper;
import com.linearlite.server.mapper.RtCertificateMapper;
import com.linearlite.server.mapper.RtGrantMapper;
import com.linearlite.server.mapper.RtProfileMapper;
import com.linearlite.server.mapper.RtTargetMapper;
import com.linearlite.server.sshca.CaKeyCipher;
import com.linearlite.server.sshca.SshCertificateSigner;
import com.linearlite.server.sshca.SshKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteTerminalCertificateServiceTest {

    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final RtCaKeyMapper caMapper = mock(RtCaKeyMapper.class);
    private final RtTargetMapper targetMapper = mock(RtTargetMapper.class);
    private final RtProfileMapper profileMapper = mock(RtProfileMapper.class);
    private final RtGrantMapper grantMapper = mock(RtGrantMapper.class);
    private final RtCertificateMapper certificateMapper = mock(RtCertificateMapper.class);
    private final RemoteTerminalProperties properties = new RemoteTerminalProperties();
    private CaKeyCipher cipher;
    private RemoteTerminalCertificateService service;
    private SshCertificateSigner.CaKeyPair ca;

    @BeforeEach
    void setUp() {
        properties.setCaKek(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new CaKeyCipher(properties);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new RemoteTerminalCertificateService(guard, projectMapper, caMapper, targetMapper, profileMapper,
                grantMapper, certificateMapper, cipher, new RemoteTerminalRateLimiter(properties, clock), clock);

        Project project = new Project();
        project.setId(1L);
        project.setIdentifier("ENG");
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);

        ca = SshCertificateSigner.generateCaKeyPair();
        RtCaKey caRow = new RtCaKey();
        caRow.setId(3L);
        caRow.setProjectId(1L);
        caRow.setStatus("active");
        caRow.setPublicKey(SshKeys.formatEd25519(ca.publicKey()) + " linear-lite ENG");
        caRow.setPrivateKeyEnc(cipher.encrypt(1L, ca.seed()));
        when(caMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(caRow);

        RtTarget target = new RtTarget();
        target.setId(10L);
        target.setProjectId(1L);
        target.setTargetId("prod-api");
        when(targetMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(target);

        RtProfile profile = new RtProfile();
        profile.setId(20L);
        profile.setTargetRef(10L);
        profile.setProfileId("deploy");
        profile.setLoginUser("deploy");
        profile.setMaxTtlSeconds(600);
        profile.setPermitPty(true);
        when(profileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        when(grantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        doAnswer(invocation -> {
            invocation.<RtCertificate>getArgument(0).setId(99L);
            return 1;
        }).when(certificateMapper).insert(any(RtCertificate.class));
    }

    @Test
    void issuesCertificateBoundToOneTargetAndLoginUser(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        String publicKey = newUserKey(dir);

        CertificateResponse response = service.issue(USER, "203.0.113.9", "client-1",
                new CertificateRequest("eng", "prod-api", "deploy", publicKey, 300));

        assertEquals(99L, response.serial());
        assertEquals("rt.ENG.prod-api.deploy", response.principal());
        assertEquals(NOW.plusSeconds(300).toString(), response.validBefore());
        Path certFile = dir.resolve("issued-cert.pub");
        Files.writeString(certFile, response.certificate() + "\n");
        String listing = run(dir, "ssh-keygen", "-L", "-f", certFile.toString());
        assertTrue(listing.contains("rt.ENG.prod-api.deploy"), listing);
        assertTrue(listing.contains("Serial: 99"), listing);
        assertTrue(listing.contains("permit-pty"), listing);
        assertTrue(listing.contains("Valid: from 2026-11-03T07:59:00 to 2026-11-03T08:05:00"), listing);
        assertTrue(listing.contains("Signing CA: ED25519 " + SshKeys.fingerprint(SshKeys.blob("ssh-ed25519", ca.publicKey()))), listing);
    }

    @Test
    void recordsWhoGotACertificateForWhichIdentity(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        service.issue(USER, "203.0.113.9", "client-1", new CertificateRequest("ENG", "prod-api", "deploy",
                newUserKey(dir), 60));

        org.mockito.ArgumentCaptor<RtCertificate> captor = org.mockito.ArgumentCaptor.forClass(RtCertificate.class);
        verify(certificateMapper).insert(captor.capture());
        RtCertificate row = captor.getValue();
        assertEquals(USER, row.getUserId());
        assertEquals(1L, row.getProjectId());
        assertEquals(10L, row.getTargetRef());
        assertEquals(20L, row.getProfileRef());
        assertEquals("rt.ENG.prod-api.deploy", row.getPrincipal());
        assertEquals("203.0.113.9", row.getClientIp());
        assertEquals("client-1", row.getOauthClientId());
        assertTrue(row.getKeyFingerprint().startsWith("SHA256:"));
    }

    @Test
    void refusesWithoutGrantAndDoesNotDistinguishUnknownTargets(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        String publicKey = newUserKey(dir);
        when(grantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        ForbiddenOperationException noGrant = assertThrows(ForbiddenOperationException.class, () -> service.issue(
                USER, "ip", "c", new CertificateRequest("ENG", "prod-api", "deploy", publicKey, 60)));

        when(targetMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        ForbiddenOperationException noTarget = assertThrows(ForbiddenOperationException.class, () -> service.issue(
                USER, "ip", "c", new CertificateRequest("ENG", "ghost", "deploy", publicKey, 60)));

        assertEquals(noGrant.getMessage(), noTarget.getMessage());
        verify(certificateMapper, never()).insert(any(RtCertificate.class));
    }

    @Test
    void enforcesProfileLifetimeAndKeyType(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        String publicKey = newUserKey(dir);
        for (Integer ttl : new Integer[] {null, 0, -1, 601}) {
            assertThrows(IllegalArgumentException.class, () -> service.issue(USER, "ip", "c",
                    new CertificateRequest("ENG", "prod-api", "deploy", publicKey, ttl)), "ttl=" + ttl);
        }
        assertThrows(IllegalArgumentException.class, () -> service.issue(USER, "ip", "c",
                new CertificateRequest("ENG", "prod-api", "deploy", "ssh-rsa AAAA", 60)));
        verify(certificateMapper, never()).insert(any(RtCertificate.class));
    }

    @Test
    void failsClosedWithoutKek(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        String publicKey = newUserKey(dir);
        properties.setCaKek("");
        assertThrows(com.linearlite.server.exception.UnprocessableEntityException.class, () -> service.issue(
                USER, "ip", "c", new CertificateRequest("ENG", "prod-api", "deploy", publicKey, 60)));
    }

    @Test
    void limitsCertificatesPerUserPerMinute(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        String publicKey = newUserKey(dir);
        properties.setCertificateRatePerMinute(2);
        CertificateRequest request = new CertificateRequest("ENG", "prod-api", "deploy", publicKey, 60);
        service.issue(USER, "ip", "c", request);
        service.issue(USER, "ip", "c", request);
        assertThrows(RemoteTerminalRateLimiter.RateLimitedException.class, () -> service.issue(USER, "ip", "c", request));
        // 其他用户不受影响
        service.issue(8L, "ip", "c", request);
    }

    @Test
    void catalogListsOnlyGrantedIdentitiesAndHidesUngrantedTargets() {
        RtGrant grant = new RtGrant();
        grant.setProfileRef(20L);
        grant.setUserId(USER);
        when(grantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(grant));
        RtProfile profile = new RtProfile();
        profile.setId(20L);
        profile.setTargetRef(10L);
        profile.setProfileId("deploy");
        profile.setLoginUser("deploy");
        profile.setMaxTtlSeconds(600);
        when(profileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(profile));
        RtTarget target = new RtTarget();
        target.setId(10L);
        target.setTargetId("prod-api");
        target.setAlias("API");
        target.setEnvironment("prod");
        target.setHost("10.0.0.5");
        target.setPort(22);
        target.setHostKey("ssh-ed25519 AAAA");
        target.setHostKeyRev(3);
        when(targetMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(target));

        Catalog catalog = service.catalog("ENG", USER);

        assertEquals("ENG", catalog.project());
        assertEquals(1, catalog.targets().size());
        assertEquals("prod-api", catalog.targets().get(0).targetId());
        assertEquals(3, catalog.targets().get(0).hostKeyRev());
        assertEquals("deploy", catalog.targets().get(0).profiles().get(0).profileId());
        verify(guard).requireMember(1L, USER);
    }

    @Test
    void catalogIsEmptyWhenTheMemberHasNoGrants() {
        when(grantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertTrue(service.catalog("ENG", USER).targets().isEmpty());
    }

    private static String newUserKey(Path dir) throws Exception {
        Path key = dir.resolve("member");
        run(dir, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString());
        return Files.readString(Path.of(key + ".pub")).trim();
    }

    private static boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("which", command).redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(Path dir, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("TZ", "UTC");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
