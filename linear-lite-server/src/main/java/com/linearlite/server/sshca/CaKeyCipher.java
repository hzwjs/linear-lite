package com.linearlite.server.sshca;

import com.linearlite.server.config.RemoteTerminalProperties;
import com.linearlite.server.exception.UnprocessableEntityException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 项目 CA 私钥种子的入库加密：nonce(12) + 密文。密钥来自 RT_CA_KEK，附加数据绑定项目 id，
 * 避免把一个项目的密文挪到另一个项目下解开。
 */
@Component
public class CaKeyCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RemoteTerminalProperties properties;

    public CaKeyCipher(RemoteTerminalProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return kek() != null;
    }

    public byte[] encrypt(long projectId, byte[] seed) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, "rt-ca:" + projectId, nonce);
            byte[] sealed = cipher.doFinal(seed);
            byte[] result = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(sealed, 0, result, nonce.length, sealed.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("CA 私钥加密失败", e);
        }
    }

    public byte[] decrypt(long projectId, byte[] stored) {
        try {
            byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, "rt-ca:" + projectId, nonce);
            return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("CA 私钥解密失败，请检查 RT_CA_KEK", e);
        }
    }

    public byte[] encryptPassword(long projectId, long profileId, String password) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, "rt-password:" + projectId + ":" + profileId, nonce);
            byte[] sealed = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(nonce, nonce.length + sealed.length);
            System.arraycopy(sealed, 0, result, nonce.length, sealed.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("登录密码加密失败", e);
        }
    }

    public String decryptPassword(long projectId, long profileId, byte[] stored) {
        try {
            byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, "rt-password:" + projectId + ":" + profileId, nonce);
            return new String(cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("登录密码解密失败，请检查 RT_CA_KEK", e);
        }
    }

    private Cipher cipher(int mode, String purpose, byte[] nonce) throws GeneralSecurityException {
        byte[] key = kek();
        if (key == null) {
            throw new UnprocessableEntityException("远程终端未启用：服务端缺少有效的 RT_CA_KEK（32 字节 Base64）");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] kek() {
        String value = properties.getCaKek();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(value.trim());
            return key.length == 32 ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
