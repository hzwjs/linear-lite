package com.linearlite.server.service;

import com.linearlite.server.config.CollaborationProperties;
import com.linearlite.server.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class CollaborationInternalKeyGuard {
    public static final String HEADER = "X-Collaboration-Internal-Key";
    private final CollaborationProperties properties;

    public CollaborationInternalKeyGuard(CollaborationProperties properties) {
        this.properties = properties;
    }

    public void requireValid(String suppliedKey) {
        String configuredKey = properties.getInternalKey();
        if (configuredKey == null || configuredKey.isBlank() || suppliedKey == null
                || !MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("内部协作请求未通过认证");
        }
    }
}
