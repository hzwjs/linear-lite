package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.AgentCredential;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.AgentCredentialMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class AgentAuthenticationService {
    private final AgentCredentialMapper credentialMapper;
    private final UserMapper userMapper;

    public AgentAuthenticationService(AgentCredentialMapper credentialMapper, UserMapper userMapper) {
        this.credentialMapper = credentialMapper;
        this.userMapper = userMapper;
    }

    public User authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("缺少 Agent Token");
        }
        String hash = hash(token.trim());
        AgentCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<AgentCredential>()
                .eq(AgentCredential::getTokenHash, hash)
                .eq(AgentCredential::getEnabled, true));
        if (credential == null || (credential.getExpiresAt() != null
                && credential.getExpiresAt().isBefore(LocalDateTime.now()))) {
            throw new UnauthorizedException("Agent Token 无效或已过期");
        }
        User user = userMapper.selectById(credential.getAgentUserId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !"agent".equals(user.getPrincipalType())) {
            throw new UnauthorizedException("Agent 主体不可用");
        }
        credential.setLastSeenAt(LocalDateTime.now());
        credentialMapper.updateById(credential);
        return user;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Agent Token 哈希", e);
        }
    }
}
