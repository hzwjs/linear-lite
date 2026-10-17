package com.linearlite.server.config;

import com.linearlite.server.mcp.McpEndpointPaths;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "mcp.oauth")
public class McpOAuthProperties {

    private boolean enabled;
    private String issuer = "";
    private String resource = "";
    private long tokenTtlSeconds = 2_592_000;
    private long refreshTokenTtlSeconds = 2_592_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return normalizeUrl(issuer);
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getResource() {
        return normalizeUrl(resource);
    }

    public String getProtectedResourceMetadataUrl() {
        return URI.create(getResource())
                .resolve(McpEndpointPaths.PROTECTED_RESOURCE_METADATA)
                .toString();
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public void validateForAuthorizationServer() {
        requireHttpsOrigin(getIssuer(), "mcp.oauth.issuer");
        requireHttpsResource(getResource(), "mcp.oauth.resource");
        if (tokenTtlSeconds < 300 || tokenTtlSeconds > 2_592_000) {
            throw new IllegalStateException("mcp.oauth.token-ttl-seconds must be between 300 and 2592000.");
        }
        if (refreshTokenTtlSeconds < 3_600 || refreshTokenTtlSeconds > 7_776_000) {
            throw new IllegalStateException("mcp.oauth.refresh-token-ttl-seconds must be between 3600 and 7776000.");
        }
    }

    private static String normalizeUrl(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void requireHttpsOrigin(String value, String propertyName) {
        URI uri = requireHttpsUrl(value, propertyName);
        if (uri.getPath() != null && !uri.getPath().isEmpty()) {
            throw new IllegalStateException(propertyName + " must be an HTTPS origin without a path.");
        }
    }

    private static void requireHttpsResource(String value, String propertyName) {
        URI uri = requireHttpsUrl(value, propertyName);
        if (!McpEndpointPaths.MCP_ENDPOINT.equals(uri.getPath())) {
            throw new IllegalStateException(propertyName + " must identify the MCP endpoint at /mcp.");
        }
    }

    private static URI requireHttpsUrl(String value, String propertyName) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(propertyName + " must be an absolute HTTPS URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(propertyName + " must be an absolute HTTPS URL.");
        }
        return uri;
    }
}
