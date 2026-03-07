package com.linearlite.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项：secret、过期时间。
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret = "linear-lite-default-secret-change-in-production";
    private long expirationMs = 86400000L;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }
}
