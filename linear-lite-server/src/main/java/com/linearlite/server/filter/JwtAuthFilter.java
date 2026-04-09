package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 对 /api/** 请求校验 JWT（除公开鉴权接口与 OPTIONS 外）。无效或缺失返回 401。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String AUTH_LOGIN_PATH = "/api/auth/login";
    private static final String AUTH_REGISTER_PATH = "/api/auth/register";
    private static final String AUTH_REGISTER_SEND_CODE_PATH = "/api/auth/register/send-code";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String REQUEST_ATTR_USER_ID = "userId";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (AUTH_LOGIN_PATH.equals(path)
                || AUTH_REGISTER_PATH.equals(path)
                || AUTH_REGISTER_SEND_CODE_PATH.equals(path)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token == null || token.isEmpty()) {
            send401(response, "缺少认证信息");
            return;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = jwtUtil.getUserIdFromClaims(claims);
            if (userId != null) {
                request.setAttribute(REQUEST_ATTR_USER_ID, userId);
            }
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException e) {
            send401(response, "无效或过期的 Token");
        }
    }

    /**
     * EventSource 无法设置 Authorization 头时，允许对通知 SSE 使用 query {@code access_token}（仅此路径）。
     */
    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        String path = request.getRequestURI();
        if (path != null && path.endsWith("/notifications/stream")
                && "GET".equalsIgnoreCase(request.getMethod())) {
            String q = request.getParameter("access_token");
            if (q != null && !q.isBlank()) {
                return q.trim();
            }
        }
        return null;
    }

    private void send401(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(401, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
