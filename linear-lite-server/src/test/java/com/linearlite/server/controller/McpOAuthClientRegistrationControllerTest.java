package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpOAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class McpOAuthClientRegistrationControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsAuthorizationCodeOnlyForClientsThatDoNotRequestRefreshTokens() throws Exception {
        AtomicReference<RegisteredClient> saved = new AtomicReference<>();
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(clients).save(any());
        McpOAuthClientRegistrationController controller =
                new McpOAuthClientRegistrationController(clients, new McpOAuthProperties());

        var response = controller.register(objectMapper.readTree("""
                {
                  "client_name": "Codex baseline",
                  "redirect_uris": ["http://127.0.0.1:33418"],
                  "grant_types": ["authorization_code"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none"
                }
                """));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(java.util.List.of("authorization_code"), response.getBody().get("grant_types"));
        assertTrue(saved.get().getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE));
        assertFalse(saved.get().getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
    }

    @Test
    void enablesRefreshTokensWhenTheNewClientRequestsThatGrant() throws Exception {
        AtomicReference<RegisteredClient> saved = new AtomicReference<>();
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(clients).save(any());
        McpOAuthClientRegistrationController controller =
                new McpOAuthClientRegistrationController(clients, new McpOAuthProperties());

        var response = controller.register(objectMapper.readTree("""
                {
                  "client_name": "Linear Lite Local Connector",
                  "redirect_uris": ["http://127.0.0.1:33418"],
                  "grant_types": ["authorization_code", "refresh_token"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none",
                  "scope": "linear-lite:read linear-lite:write"
                }
                """));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(java.util.List.of("authorization_code", "refresh_token"), response.getBody().get("grant_types"));
        assertTrue(saved.get().getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE));
        assertTrue(saved.get().getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
        assertFalse(saved.get().getTokenSettings().isReuseRefreshTokens());
    }

    @Test
    void acceptsTheVsCodeCallbackPair() throws Exception {
        AtomicReference<RegisteredClient> saved = new AtomicReference<>();
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(clients).save(any());
        McpOAuthClientRegistrationController controller =
                new McpOAuthClientRegistrationController(clients, new McpOAuthProperties());

        var response = controller.register(objectMapper.readTree("""
                {
                  "client_name": "VS Code",
                  "redirect_uris": ["http://127.0.0.1:33418", "https://vscode.dev/redirect"],
                  "grant_types": ["authorization_code"],
                  "response_types": ["code"],
                  "token_endpoint_auth_method": "none"
                }
                """));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(2, saved.get().getRedirectUris().size());
    }
}
