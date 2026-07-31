package com.linearlite.server.util;

import com.linearlite.server.config.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** GitHub Secret 使用应用密钥加密落库，避免 HMAC 密钥以明文持久化。 */
@Component
public class WebhookSecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JwtProperties properties;
    public WebhookSecretCipher(JwtProperties properties) { this.properties = properties; }
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Webhook Secret 加密失败", e); }
    }
    public String decrypt(String ciphertext) {
        try {
            String[] parts = ciphertext.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Webhook Secret 密文格式无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getUrlDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Webhook Secret 解密失败", e); }
    }
    private SecretKeySpec key() throws Exception { return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(properties.getSecret().getBytes(StandardCharsets.UTF_8)), "AES"); }
}
