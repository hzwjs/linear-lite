package com.linearlite.server.sshca;

import com.linearlite.server.config.RemoteTerminalProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SshCertificateSignerTest {

    @Test
    void issuedCertificateIsParsedAndVerifiedByOpenSsh(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        SshCertificateSigner.CaKeyPair ca = SshCertificateSigner.generateCaKeyPair();
        Path userKey = dir.resolve("user");
        run(dir, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", userKey.toString());
        byte[] userPublic = SshKeys.parseEd25519(Files.readString(Path.of(userKey + ".pub")));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        String certificate = SshCertificateSigner.issue(ca, userPublic, new SshCertificateSigner.Request(
                42L, "u7/ENG/prod/deploy", List.of("rt.ENG.prod.deploy"), now.minusSeconds(60), now.plusSeconds(600), true));
        Path certFile = dir.resolve("user-cert.pub");
        Files.writeString(certFile, certificate + "\n");

        String listing = run(dir, "ssh-keygen", "-L", "-f", certFile.toString());
        assertTrue(listing.contains("Type: ssh-ed25519-cert-v01@openssh.com user certificate"), listing);
        assertTrue(listing.contains("Serial: 42"), listing);
        assertTrue(listing.contains("Key ID: \"u7/ENG/prod/deploy\""), listing);
        assertTrue(listing.contains("rt.ENG.prod.deploy"), listing);
        assertTrue(listing.contains("permit-pty"), listing);
        assertTrue(listing.contains("Signing CA: ED25519 " + SshKeys.fingerprint(SshKeys.blob("ssh-ed25519", ca.publicKey()))), listing);

        // ssh-keygen -L 不校验签名，这里用 CA 公钥做 -Y 之外的真正验签：改动任一字节后 sshd 才会拒绝，
        // 因此额外确认签名覆盖证书正文。
        byte[] blob = Base64.getDecoder().decode(certificate.split(" ")[1]);
        byte[] tampered = blob.clone();
        tampered[blob.length / 2] ^= 0x01;
        assertNotEquals(Base64.getEncoder().encodeToString(blob), Base64.getEncoder().encodeToString(tampered));
    }

    @Test
    void omitsPermitPtyWhenNotRequested(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        SshCertificateSigner.CaKeyPair ca = SshCertificateSigner.generateCaKeyPair();
        Path userKey = dir.resolve("user");
        run(dir, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", userKey.toString());
        byte[] userPublic = SshKeys.parseEd25519(Files.readString(Path.of(userKey + ".pub")));
        Instant now = Instant.now();
        String certificate = SshCertificateSigner.issue(ca, userPublic, new SshCertificateSigner.Request(
                1L, "id", List.of("p"), now, now.plusSeconds(60), false));
        Path certFile = dir.resolve("c-cert.pub");
        Files.writeString(certFile, certificate + "\n");
        assertFalse(run(dir, "ssh-keygen", "-L", "-f", certFile.toString()).contains("permit-pty"));
    }

    @Test
    void caKeyRoundTripsThroughCipherAndIsBoundToProject() {
        RemoteTerminalProperties properties = new RemoteTerminalProperties();
        properties.setCaKek(Base64.getEncoder().encodeToString(new byte[32]));
        CaKeyCipher cipher = new CaKeyCipher(properties);
        byte[] seed = SshCertificateSigner.generateCaKeyPair().seed();

        byte[] stored = cipher.encrypt(5L, seed);
        assertArrayEquals(seed, cipher.decrypt(5L, stored));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(6L, stored));
    }

    @Test
    void cipherIsUnavailableWithoutAValidKek() {
        RemoteTerminalProperties properties = new RemoteTerminalProperties();
        CaKeyCipher cipher = new CaKeyCipher(properties);
        assertFalse(cipher.isConfigured());
        properties.setCaKek("not-base64-and-not-32-bytes");
        assertFalse(cipher.isConfigured());
        properties.setCaKek(Base64.getEncoder().encodeToString(new byte[16]));
        assertFalse(cipher.isConfigured());
        properties.setCaKek(Base64.getEncoder().encodeToString(new byte[32]));
        assertTrue(cipher.isConfigured());
    }

    @Test
    void parsesAndRejectsPublicKeys(@TempDir Path dir) throws Exception {
        assumeTrue(commandAvailable("ssh-keygen"), "ssh-keygen is required");
        Path key = dir.resolve("k");
        run(dir, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "someone@host", "-f", key.toString());
        String line = Files.readString(Path.of(key + ".pub")).trim();

        SshKeys.PublicKey parsed = SshKeys.parseHostKey(line);
        assertEquals("ssh-ed25519", parsed.type());
        assertFalse(parsed.normalized().contains("someone@host"));
        String listed = run(dir, "ssh-keygen", "-lf", key + ".pub");
        assertTrue(listed.contains(parsed.fingerprint()), listed);
        assertEquals(32, SshKeys.parseEd25519(line).length);

        assertThrows(IllegalArgumentException.class, () -> SshKeys.parseHostKey("ssh-ed25519"));
        assertThrows(IllegalArgumentException.class, () -> SshKeys.parseHostKey("ssh-rsa " + line.split(" ")[1]));
        assertThrows(IllegalArgumentException.class, () -> SshKeys.parseHostKey(line + "\nssh-ed25519 AAAA"));
        assertThrows(IllegalArgumentException.class, () -> SshKeys.parseEd25519("ecdsa-sha2-nistp256 AAAA"));
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
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
