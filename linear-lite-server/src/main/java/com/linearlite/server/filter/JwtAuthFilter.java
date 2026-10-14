package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.util.DocumentAssetCookie;
import com.linearlite.server.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 对 /api/** 请求校验 JWT（除公开鉴权接口与 OPTIONS 外）。无效或缺失返回 401。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String AUTH_LOGIN_PATH = "/api/auth/login";
    private static final String AUTH_REGISTER_PATH = "/api/auth/register";
    private static final String AUTH_REGISTER_SEND_CODE_PATH = "/api/auth/register/send-code";
    private static final String AUTH_PASSWORD_RESET_PATH = "/api/auth/password-reset";
    private static final String AUTH_PASSWORD_RESET_SEND_CODE_PATH = "/api/auth/password-reset/send-code";
    private static final String AUTH_LOGOUT_PATH = "/api/auth/logout";
    private static final String DOCUMENT_ASSET_PREFIX = "/api/document-assets/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String REQUEST_ATTR_USER_ID = "userId";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final McpOAuthProperties mcpOAuthProperties;
    private final OAuth2AuthorizationService authorizationService;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtAuthFilter(JwtUtil jwtUtil, ObjectMapper objectMapper, McpOAuthProperties mcpOAuthProperties,
                         ObjectProvider<OAuth2AuthorizationService> authorizationServiceProvider) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.mcpOAuthProperties = mcpOAuthProperties;
        this.authorizationService = authorizationServiceProvider.getIfAvailable();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean protectedApiPath = path.startsWith("/api/") || "/mcp".equals(path);
        if (!protectedApiPath) {
            return true;
        }
        // Bridge API 使用执行凭据认证；安装包下载也不依赖人类 JWT 认证链路。
        if (path.startsWith("/api/bridge/")) {
            return true;
        }
        // 新规范只允许 POST；让旧 GET/DELETE 请求直接得到 405，而不是被认证层改写为 401。
        if ("/mcp".equals(path)
                && ("GET".equalsIgnoreCase(request.getMethod()) || "DELETE".equalsIgnoreCase(request.getMethod()))) {
            return true;
        }
        if ("/api/webhooks/gitlab".equals(path) || "/api/webhooks/github".equals(path)) {
            return true;
        }
        if (AUTH_LOGIN_PATH.equals(path)
                || AUTH_REGISTER_PATH.equals(path)
                || AUTH_REGISTER_SEND_CODE_PATH.equals(path)
                || AUTH_PASSWORD_RESET_PATH.equals(path)
                || AUTH_LOGOUT_PATH.equals(path)
                || AUTH_PASSWORD_RESET_SEND_CODE_PATH.equals(path)) {
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
        boolean mcpRequest = "/mcp".equals(request.getRequestURI());
        if (mcpRequest && !mcpOAuthProperties.isEnabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            ApiResponse<Void> body = ApiResponse.fail(503, "MCP OAuth is not configured.");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }
        String token = resolveToken(request);

        if (token == null || token.isEmpty()) {
            if (mcpRequest) {
                sendMcp401(response, "缺少 OAuth 访问令牌", null);
            } else {
                send401(response, "缺少认证信息");
            }
            return;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            if (mcpRequest) {
                Long userId = validateMcpAccessToken(token, claims);
                request.setAttribute(REQUEST_ATTR_USER_ID, userId);
                request.setAttribute("mcpScopes", extractScopes(claims));
            } else {
                Long userId = jwtUtil.getUserIdFromClaims(claims);
                if (userId != null) {
                    request.setAttribute(REQUEST_ATTR_USER_ID, userId);
                }
            }
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException e) {
            if (mcpRequest) {
                LOGGER.warn("Rejected MCP OAuth access token: {}", e.getMessage());
                sendMcp401(response, "OAuth 访问令牌无效或权限已过期", "invalid_token");
            } else {
                send401(response, "无效或过期的 Token");
            }
        }
    }

    private Long validateMcpAccessToken(String token, Claims claims) {
        if (!mcpOAuthProperties.getIssuer().equals(claims.getIssuer())
                || claims.getExpiration() == null
                || claims.getAudience() == null
                || !claims.getAudience().contains(mcpOAuthProperties.getResource())) {
            throw new IllegalArgumentException("The OAuth token does not target Linear Lite MCP.");
        }
        if (authorizationService == null) {
            throw new IllegalArgumentException("OAuth authorization state is unavailable.");
        }
        OAuth2Authorization authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null || authorization.getAccessToken() == null
                || !authorization.getAccessToken().isActive()) {
            throw new IllegalArgumentException("The OAuth token has been revoked or is not active.");
        }
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The OAuth token has no Linear Lite account.");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("The OAuth token has no Linear Lite account.");
        }
        extractScopes(claims);
        return userId;
    }

    private Set<String> extractScopes(Claims claims) {
        Object value = claims.get("scope");
        Set<String> scopes = new LinkedHashSet<>();
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (item instanceof String scope && !scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        } else {
            throw new IllegalArgumentException("The OAuth token contains no scope claim.");
        }
        if (!Set.of("linear-lite:read", "linear-lite:write").containsAll(scopes)) {
            throw new IllegalArgumentException("The OAuth token contains an unsupported scope.");
        }
        return Set.copyOf(scopes);
    }

    /**
     * EventSource 无法设置 Authorization 头时，允许对受保护 SSE 使用 query {@code access_token}。
     */
    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        String path = request.getRequestURI();
        // 图片端点由原生 <img> 访问，无法携带 Bearer 头，改用路径级 HttpOnly Cookie。
        if (path != null && path.startsWith(DOCUMENT_ASSET_PREFIX)) {
            String cookieToken = DocumentAssetCookie.read(request);
            if (cookieToken != null && !cookieToken.isBlank()) {
                return cookieToken.trim();
            }
        }
        if (path != null && (path.endsWith("/notifications/stream") || path.matches(".*/local-pi/sessions/[^/]+/stream$"))
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
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(401, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void sendMcp401(HttpServletResponse response, String message, String error) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        String challenge = "Bearer resource_metadata=\""
                + mcpOAuthProperties.getProtectedResourceMetadataUrl() + "\"";
        if (error != null) {
            challenge += ", error=\"" + error + "\", error_description=\"The access token is invalid or expired.\"";
        }
        response.setHeader("WWW-Authenticate", challenge);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(401, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
