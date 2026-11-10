package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** HMAC 支持领取响应重放，持久化记录只保存领取标识摘要。 */
@Service
public class TaskExecutionClaimCredentialService {
    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    public TaskExecutionClaimCredentialService(JwtProperties jwtProperties, ObjectMapper objectMapper) {
        this.jwtProperties = jwtProperties;
        this.objectMapper = objectMapper;
    }

    public String issue(String batchId, Long bindingId, Long taskId, String hostInstanceId,
                        String agentInstanceId, String operationId) {
        if (batchId == null || batchId.isBlank() || bindingId == null || bindingId <= 0
                || taskId == null || taskId <= 0 || hostInstanceId == null || hostInstanceId.isBlank()
                || agentInstanceId == null || agentInstanceId.isBlank()
                || operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("领取归属参数无效");
        }
        try {
            // 数组序列化保留参数边界；用途域与登录及批次签名隔离。
            byte[] input = objectMapper.writeValueAsBytes(List.of("linear-lite:task-claim:v1", batchId,
                    bindingId, taskId, hostInstanceId, agentInstanceId, operationId));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input));
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException("领取标识生成失败");
        }
    }

    public String hash(String claimToken) {
        if (claimToken == null || claimToken.isBlank()) throw new IllegalArgumentException("领取标识不能为空");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(claimToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("领取标识校验失败"); }
    }

    public boolean matches(String claimToken, String expectedHash) {
        if (claimToken == null || claimToken.isBlank() || expectedHash == null) return false;
        return MessageDigest.isEqual(hash(claimToken).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
