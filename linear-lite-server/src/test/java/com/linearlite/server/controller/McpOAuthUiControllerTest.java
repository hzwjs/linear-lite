package com.linearlite.server.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import com.linearlite.server.security.OAuthResourcePolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpOAuthUiControllerTest {

    private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    private final McpOAuthUiController controller = new McpOAuthUiController(clients);

    @Test
    void loginPageUsesClientNeutralLanguage() {
        var response = controller.login(mock(HttpServletRequest.class), null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("授权请求中的应用"));
        assertFalse(response.getBody().contains("Codex"));
    }

    @Test
    void consentUsesEscapedRegisteredClientNameAndEscapedHiddenFields() {
        String clientId = "registered-client";
        String clientName = "<img src=x onerror=alert(1)>&\"'";
        when(clients.findByClientId(clientId)).thenReturn(registeredClient(clientId, clientName));

        var response = controller.consent(clientId, "state<&", "linear-lite:read", () -> "user<&");
        String page = response.getBody();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(page.contains("&lt;img src=x onerror=alert(1)&gt;&amp;&quot;&#39;"));
        assertTrue(page.contains("value=\"registered-client\""));
        assertTrue(page.contains("value=\"state&lt;&amp;\""));
        assertTrue(page.contains("user&lt;&amp;"));
        assertFalse(page.contains(clientName));
        assertFalse(page.contains("Codex"));
    }

    @Test
    void unknownClientGetsBadRequestWithoutAuthorizationFormOrClientId() {
        when(clients.findByClientId("unknown-client")).thenReturn(null);

        var response = controller.consent("unknown-client", "private-state", "linear-lite:read", () -> "user");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().contains("<form"));
        assertFalse(response.getBody().contains("unknown-client"));
        assertFalse(response.getBody().contains("private-state"));
    }

    @Test
    void invalidScopeRemainsRejectedForRegisteredClient() {
        when(clients.findByClientId("registered-client"))
                .thenReturn(registeredClient("registered-client", "Daily Report Assistant"));

        var response = controller.consent("registered-client", "state", "unknown:scope", () -> "user");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().contains("<form"));
    }

    @Test
    void consentShowsEscapedRegisteredDeviceIdentity() {
        RegisteredClient client = RegisteredClient.withId("internal-host").clientId("host")
                .clientName("Host").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:1234/callback")
                .scope(OAuthResourcePolicy.HOST_SCOPE)
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                        .setting(OAuthResourcePolicy.DEVICE_ID_SETTING, "<device>")
                        .setting(OAuthResourcePolicy.DEVICE_NAME_SETTING, "<Mac>").build()).build();
        when(clients.findByClientId("host")).thenReturn(client);
        var response = controller.consent("host", "state", OAuthResourcePolicy.HOST_SCOPE, () -> "user");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("&lt;device&gt;"));
        assertTrue(response.getBody().contains("&lt;Mac&gt;"));
        assertFalse(response.getBody().contains("<Mac>"));
    }

    private static RegisteredClient registeredClient(String clientId, String clientName) {
        return RegisteredClient.withId("internal-" + clientId)
                .clientId(clientId)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1/callback")
                .build();
    }
}
