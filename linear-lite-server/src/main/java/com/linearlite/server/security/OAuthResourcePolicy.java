package com.linearlite.server.security;

import com.linearlite.server.config.McpOAuthProperties;

import java.util.Set;

/** OAuth 的 scope 与资源用途由同一策略确定。 */
public final class OAuthResourcePolicy {
    public static final String HOST_SCOPE = "collaboration-host";
    public static final String REMOTE_SCOPE = "remote-terminal";
    public static final String DEVICE_ID_SETTING = "linear-lite.device-id";
    public static final String DEVICE_NAME_SETTING = "linear-lite.device-name";
    public static final String AUTHORIZATION_ID_CLAIM = "authorization_id";
    private static final Set<String> MCP_SCOPES = Set.of("linear-lite:read", "linear-lite:write");

    private OAuthResourcePolicy() {}

    public static String hostResource(McpOAuthProperties properties) {
        return properties.getIssuer() + "/api/collaboration-host";
    }

    public static String resourceFor(Set<String> scopes, McpOAuthProperties properties) {
        if (scopes.equals(Set.of(HOST_SCOPE))) return hostResource(properties);
        if (scopes.equals(Set.of(REMOTE_SCOPE)) || !scopes.isEmpty() && MCP_SCOPES.containsAll(scopes)) {
            return properties.getResource();
        }
        throw new IllegalArgumentException("OAuth scope 不符合用途");
    }

    public static boolean isHost(Set<String> scopes) { return scopes.equals(Set.of(HOST_SCOPE)); }

}
