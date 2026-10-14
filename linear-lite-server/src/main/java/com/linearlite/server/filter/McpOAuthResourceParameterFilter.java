package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.config.McpOAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class McpOAuthResourceParameterFilter extends OncePerRequestFilter {

    private final McpOAuthProperties properties;
    private final ObjectMapper objectMapper;

    public McpOAuthResourceParameterFilter(McpOAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return !("/oauth2/authorize".equals(path) || "/oauth2/token".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isConsentSubmission(request)) {
            // Consent is bound to the resource in the persisted authorization request identified by state.
            filterChain.doFilter(request, response);
            return;
        }
        if (!properties.getResource().equals(request.getParameter("resource"))) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", "invalid_target");
            error.put("error_description", "The requested resource does not match Linear Lite MCP.");
            response.getWriter().write(objectMapper.writeValueAsString(error));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isConsentSubmission(HttpServletRequest request) {
        return "/oauth2/authorize".equals(request.getRequestURI())
                && "POST".equalsIgnoreCase(request.getMethod())
                && request.getParameter("response_type") == null;
    }
}
