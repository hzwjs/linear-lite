package com.linearlite.server.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthRedirectUrisTest {

    @Test
    void allowsVsCodeLoopbackAndExactHttpsRedirect() {
        assertTrue(McpOAuthRedirectUris.isAllowed("http://127.0.0.1:33418"));
        assertTrue(McpOAuthRedirectUris.isAllowed("https://vscode.dev/redirect"));
        assertFalse(McpOAuthRedirectUris.isAllowed("https://vscode.dev/other"));
        assertFalse(McpOAuthRedirectUris.isAllowed("https://evil.vscode.dev/redirect"));
    }

    @Test
    void loopbackCallbackCanUseTheAgentChosenPort() {
        assertTrue(McpOAuthRedirectUris.matches(
                "http://127.0.0.1:43210",
                Set.of("http://127.0.0.1:33418")));
    }

    @Test
    void allowsOnlyTheDocumentedMixedVsCodeCallbackPair() {
        assertTrue(McpOAuthRedirectUris.isAllowedRegistrationSet(Set.of(
                "http://127.0.0.1:33418", "https://vscode.dev/redirect")));
        assertFalse(McpOAuthRedirectUris.isAllowedRegistrationSet(Set.of(
                "http://127.0.0.1:33418", "https://chatgpt.com/connector_platform_oauth_redirect")));
    }
}
