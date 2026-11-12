package com.linearlite.server.security;

import com.linearlite.server.config.McpOAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HostAuthorizationRequestProviderTest {
    @Test
    void registeredHostCannotOmitScopeOrDowngradeResourceOrPkce() {
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setIssuer("https://example.test");
        properties.setResource("https://example.test/mcp");
        RegisteredClient client = RegisteredClient.withId("host-client").clientId("host-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:1234/callback").scope(OAuthResourcePolicy.HOST_SCOPE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build()).build();
        var provider = new OAuth2AuthorizationCodeRequestAuthenticationProvider(
                new InMemoryRegisteredClientRepository(client), new InMemoryOAuth2AuthorizationService(),
                new InMemoryOAuth2AuthorizationConsentService());
        Authentication user = new UsernamePasswordAuthenticationToken("user", "");
        assertThrows(OAuth2AuthenticationException.class, () -> provider.authenticate(request(user, Set.of(),
                properties.getResource(), "plain")));
        assertThrows(OAuth2AuthenticationException.class, () -> provider.authenticate(request(user,
                Set.of(OAuthResourcePolicy.HOST_SCOPE), OAuthResourcePolicy.hostResource(properties), "plain")));
        assertNotNull(provider.authenticate(request(user, Set.of(OAuthResourcePolicy.HOST_SCOPE),
                OAuthResourcePolicy.hostResource(properties), "S256")));
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationToken request(Authentication user, Set<String> scopes,
                                                                              String resource, String method) {
        return new OAuth2AuthorizationCodeRequestAuthenticationToken("https://example.test/oauth2/authorize",
                "host-client", user, "http://127.0.0.1:1234/callback", "state", scopes,
                Map.of("resource", resource, "code_challenge_method", method, "code_challenge", "challenge"));
    }
}
