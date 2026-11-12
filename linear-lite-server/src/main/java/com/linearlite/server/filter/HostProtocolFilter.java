package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.common.HostProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 OAuth 和免 OAuth 恢复分流前拒绝旧宿主路径。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class HostProtocolFilter extends OncePerRequestFilter {
    private static final Set<String> RETIRED_EXECUTION_PATHS = Set.of(
            "/api/collaboration/batches/issue", "/api/collaboration/environment/observe",
            "/api/collaboration/tasks/claim", "/api/collaboration/attempts/expand-scopes",
            "/api/collaboration/attempts/renew", "/api/collaboration/attempts/checkpoint",
            "/api/collaboration/attempts/complete", "/api/collaboration/attempts/request-stop",
            "/api/collaboration/attempts/recovery", "/api/collaboration/attempts/recover-claim",
            "/api/collaboration/attempts/confirm-stop");
    private final ObjectMapper json;

    public HostProtocolFilter(ObjectMapper json) { this.json = json; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || (!path.startsWith(HostProtocol.BASE_PATH + "/") && !RETIRED_EXECUTION_PATHS.contains(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HostProtocol.HEADER, Integer.toString(HostProtocol.VERSION));
        if (request.getRequestURI().startsWith(HostProtocol.API_PATH + "/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(426);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), ApiResponse.fail(426, HostProtocol.UPGRADE_MESSAGE));
    }
}
