package com.linearlite.server.util;

import com.linearlite.server.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析。subject 存用户 id（Long 转 String）。
 */
@Component
public class JwtUtil {

    private final JwtProperties props;

    public JwtUtil(JwtProperties props) {
        this.props = props;
    }

    private SecretKey signingKey() {
        byte[] keyBytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getExpirationMs()))
                .signWith(signingKey())
                .compact();
    }

    /**
     * 解析并校验 JWT，返回 payload；无效或过期抛出异常。
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException | MalformedJwtException | SignatureException | IllegalArgumentException e) {
            throw new IllegalArgumentException("无效或过期的 Token: " + e.getMessage());
        }
    }

    public Long getUserIdFromClaims(Claims claims) {
        String sub = claims.getSubject();
        return sub != null ? Long.parseLong(sub) : null;
    }
}
