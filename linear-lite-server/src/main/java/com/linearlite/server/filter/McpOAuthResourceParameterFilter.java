package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.security.OAuthResourcePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
        String resource = request.getParameter("resource");
        String[] resourceValues = request.getParameterValues("resource");
        boolean valid = resourceValues != null && resourceValues.length == 1
                && (properties.getResource().equals(resource) || OAuthResourcePolicy.hostResource(properties).equals(resource));
        if (valid && "/oauth2/authorize".equals(request.getRequestURI())) {
            String scope = request.getParameter("scope");
            Set<String> scopes = scope == null ? Set.of() : Arrays.stream(scope.trim().split("\\s+"))
                    .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
            try {
                if (!scopes.isEmpty()) valid = resource.equals(OAuthResourcePolicy.resourceFor(scopes, properties));
                else if (resource.equals(OAuthResourcePolicy.hostResource(properties))) valid = false;
                if (OAuthResourcePolicy.isHost(scopes)) {
                    String[] methods = request.getParameterValues("code_challenge_method");
                    valid = valid && methods != null && methods.length == 1 && "S256".equals(methods[0]);
                }
            } catch (IllegalArgumentException exception) {
                valid = false;
            }
        }
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            ObjectNode error = objectMapper.createObjectNode();
            error.put("error", "invalid_target");
            error.put("error_description", "The requested resource, scope or PKCE method is invalid.");
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
