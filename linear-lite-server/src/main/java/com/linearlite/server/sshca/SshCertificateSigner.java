package com.linearlite.server.sshca;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 按 OpenSSH PROTOCOL.certkeys 编码并签发 ssh-ed25519-cert-v01 用户证书。
 * CA 与被签发的临时密钥都是 Ed25519，JDK 17 自带实现，无需额外依赖。
 */
public final class SshCertificateSigner {

    private static final String CERT_TYPE = "ssh-ed25519-cert-v01@openssh.com";
    private static final String KEY_TYPE = "ssh-ed25519";
    private static final byte[] PKCS8_PREFIX = {
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20};
    private static final SecureRandom RANDOM = new SecureRandom();

    private SshCertificateSigner() {
    }

    /** seed 是 32 字节 Ed25519 私钥种子，加密入库；publicKey 是 32 字节原始公钥。 */
    public record CaKeyPair(byte[] seed, byte[] publicKey) {
    }

    public record Request(long serial, String keyId, List<String> principals, Instant validAfter,
                          Instant validBefore, boolean permitPty) {
    }

    public static CaKeyPair generateCaKeyPair() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] x509 = pair.getPublic().getEncoded();
            return new CaKeyPair(tail(pkcs8, 32), tail(x509, 32));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成 CA 密钥", e);
        }
    }

    /** 返回 authorized_keys 风格的单行证书（类型 Base64 注释）。 */
    public static String issue(CaKeyPair ca, byte[] userPublicKey, Request request) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        SshKeys.writeString(body, CERT_TYPE.getBytes(StandardCharsets.US_ASCII));
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        SshKeys.writeString(body, nonce);
        SshKeys.writeString(body, userPublicKey);
        body.writeBytes(ByteBuffer.allocate(8).putLong(request.serial()).array());
        body.writeBytes(ByteBuffer.allocate(4).putInt(1).array());
        SshKeys.writeString(body, request.keyId().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream principals = new ByteArrayOutputStream();
        for (String principal : request.principals()) {
            SshKeys.writeString(principals, principal.getBytes(StandardCharsets.UTF_8));
        }
        SshKeys.writeString(body, principals.toByteArray());
        body.writeBytes(ByteBuffer.allocate(8).putLong(request.validAfter().getEpochSecond()).array());
        body.writeBytes(ByteBuffer.allocate(8).putLong(request.validBefore().getEpochSecond()).array());
        SshKeys.writeString(body, new byte[0]);
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        if (request.permitPty()) {
            SshKeys.writeString(extensions, "permit-pty".getBytes(StandardCharsets.US_ASCII));
            SshKeys.writeString(extensions, new byte[0]);
        }
        SshKeys.writeString(body, extensions.toByteArray());
        SshKeys.writeString(body, new byte[0]);
        SshKeys.writeString(body, SshKeys.blob(KEY_TYPE, ca.publicKey()));

        byte[] signature = sign(ca.seed(), body.toByteArray());
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        SshKeys.writeString(wrapped, KEY_TYPE.getBytes(StandardCharsets.US_ASCII));
        SshKeys.writeString(wrapped, signature);
        SshKeys.writeString(body, wrapped.toByteArray());
        return CERT_TYPE + " " + Base64.getEncoder().encodeToString(body.toByteArray()) + " linear-lite";
    }

    static byte[] sign(byte[] seed, byte[] data) {
        try {
            byte[] pkcs8 = new byte[PKCS8_PREFIX.length + seed.length];
            System.arraycopy(PKCS8_PREFIX, 0, pkcs8, 0, PKCS8_PREFIX.length);
            System.arraycopy(seed, 0, pkcs8, PKCS8_PREFIX.length, seed.length);
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("证书签名失败", e);
        }
    }

    private static byte[] tail(byte[] value, int length) {
        byte[] result = new byte[length];
        System.arraycopy(value, value.length - length, result, 0, length);
        return result;
    }
}
