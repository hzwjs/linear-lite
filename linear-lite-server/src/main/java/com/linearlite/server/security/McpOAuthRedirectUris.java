package com.linearlite.server.security;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

    /** Restricts public OAuth clients to supported browser callbacks and native-app loopback listeners. */
public final class McpOAuthRedirectUris {

    private McpOAuthRedirectUris() {
    }

    public static boolean isAllowed(String value) {
        URI uri = parse(value);
        if (uri == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getHost() == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getRawPath();
            return uri.getPort() == -1 && (("chatgpt.com".equalsIgnoreCase(uri.getHost())
                    && ("/connector_platform_oauth_redirect".equals(path)
                    || path != null && path.matches("/connector/oauth/[A-Za-z0-9_-]+")))
                    || "vscode.dev".equalsIgnoreCase(uri.getHost()) && "/redirect".equals(path));
        }
        return "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost())
                && uri.getPort() > 0 && uri.getPort() <= 65535;
    }

    public static boolean matches(String requested, Set<String> registered) {
        if (!isAllowed(requested) || registered == null) {
            return false;
        }
        if (registered.contains(requested)) {
            return true;
        }
        URI requestUri = URI.create(requested);
        if (!isLoopback(requestUri.getHost())) {
            return false;
        }
        // Native clients choose a free loopback port each time they open a listener.
        return registered.stream().filter(McpOAuthRedirectUris::isAllowed).anyMatch(value -> {
            URI registeredUri = URI.create(value);
            return "http".equalsIgnoreCase(registeredUri.getScheme())
                    && requestUri.getHost().equalsIgnoreCase(registeredUri.getHost())
                    && Objects.equals(requestUri.getRawPath(), registeredUri.getRawPath());
        });
    }

    public static boolean isAllowedRegistrationSet(Set<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.stream().anyMatch(uri -> !isAllowed(uri))) {
            return false;
        }
        Set<String> schemes = redirectUris.stream()
                .map(uri -> URI.create(uri).getScheme().toLowerCase())
                .collect(Collectors.toSet());
        if (schemes.size() == 1) {
            return true;
        }
        return redirectUris.size() == 2
                && redirectUris.contains("https://vscode.dev/redirect")
                && redirectUris.stream().anyMatch(uri -> {
                    URI parsed = URI.create(uri);
                    return "http".equalsIgnoreCase(parsed.getScheme())
                            && "127.0.0.1".equals(parsed.getHost())
                            && parsed.getPort() == 33418
                            && (parsed.getRawPath() == null || parsed.getRawPath().isEmpty());
                });
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "[::1]".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static URI parse(String value) {
        if (value == null || value.length() > 500) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
