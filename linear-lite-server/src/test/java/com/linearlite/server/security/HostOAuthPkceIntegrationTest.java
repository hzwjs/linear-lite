package com.linearlite.server.security;

import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.service.HostDeviceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.PublicClientAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostOAuthPkceIntegrationTest {
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final String DEVICE = "a1111111-2222-4333-8444-555555555555";
    private static final String REDIRECT = "http://127.0.0.1:33418/callback";
    private InMemoryOAuth2AuthorizationService authorizations;
    private PublicClientAuthenticationProvider clients;
    private McpOAuthAuthorizationCodeAuthenticationProvider codes;
    private HostDeviceAuthorizationService hosts;
    private McpOAuthProperties properties;
    private RegisteredClient client;

    @BeforeEach
    void setup() throws Exception {
        AuthorizationServerContextHolder.setContext(mock(AuthorizationServerContext.class));
        properties = new McpOAuthProperties();
        properties.setIssuer("https://example.test");
        properties.setResource("https://example.test/mcp");
        client = RegisteredClient.withId("client-id").clientId("host-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT).scope(OAuthResourcePolicy.HOST_SCOPE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true)
                        .setting(OAuthResourcePolicy.DEVICE_ID_SETTING, DEVICE)
                        .setting(OAuthResourcePolicy.DEVICE_NAME_SETTING, "Mac").build()).build();
        authorizations = new InMemoryOAuth2AuthorizationService();
        clients = new PublicClientAuthenticationProvider(new InMemoryRegisteredClientRepository(client), authorizations);
        OAuth2TokenGenerator<OAuth2Token> generator = context -> OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                ? new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", Instant.now(),
                    Instant.now().plusSeconds(300), context.getAuthorizedScopes()) : null;
        var delegate = new OAuth2AuthorizationCodeAuthenticationProvider(authorizations, generator);
        McpOAuthRefreshTokenStore refresh = new McpOAuthRefreshTokenStore(mock(JdbcOperations.class));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        hosts = mock(HostDeviceAuthorizationService.class);
        codes = new McpOAuthAuthorizationCodeAuthenticationProvider(delegate, authorizations, refresh,
                properties, new TransactionTemplate(transactions), hosts);
    }

    @AfterEach
    void clearContext() { AuthorizationServerContextHolder.resetContext(); }

    @Test
    void realPkceVerifierAndAuthorizationCodeAreRequired() throws Exception {
        saveCode("code-one", Instant.now().plusSeconds(180));
        assertThrows(OAuth2AuthenticationException.class, () -> clientAuth("code-one", "wrong-verifier"));
        verifyNoInteractions(hosts);
        Authentication authenticated = clientAuth("code-one", VERIFIER);
        OAuth2AuthorizationCodeAuthenticationToken exchange = exchange("code-one", authenticated,
                OAuthResourcePolicy.hostResource(properties));
        var result = (OAuth2AccessTokenAuthenticationToken) codes.authenticate(exchange);
        assertEquals("access-token", result.getAccessToken().getTokenValue());
        verify(hosts).register("authorization-id", 7L, DEVICE, "Mac");
        assertThrows(OAuth2AuthenticationException.class, () -> codes.authenticate(exchange));
        verify(hosts, times(1)).register(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void wrongResourceAndExpiredCodeCannotCreateHostGrant() throws Exception {
        saveCode("code-one", Instant.now().plusSeconds(180));
        Authentication authenticated = clientAuth("code-one", VERIFIER);
        assertThrows(OAuth2AuthenticationException.class,
                () -> codes.authenticate(exchange("code-one", authenticated, properties.getResource())));
        verifyNoInteractions(hosts);

        saveCode("expired-code", Instant.now().minusSeconds(30));
        Authentication expiredClient = clientAuth("expired-code", VERIFIER);
        assertThrows(OAuth2AuthenticationException.class,
                () -> codes.authenticate(exchange("expired-code", expiredClient, OAuthResourcePolicy.hostResource(properties))));
        verifyNoInteractions(hosts);
    }

    private Authentication clientAuth(String code, String verifier) {
        return clients.authenticate(new OAuth2ClientAuthenticationToken("host-client", ClientAuthenticationMethod.NONE,
                null, Map.of("grant_type", "authorization_code", "code", code, "code_verifier", verifier)));
    }

    private OAuth2AuthorizationCodeAuthenticationToken exchange(String code, Authentication authenticated, String resource) {
        return new OAuth2AuthorizationCodeAuthenticationToken(code, authenticated, REDIRECT, Map.of("resource", resource));
    }

    private void saveCode(String value, Instant expiresAt) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://example.test/oauth2/authorize").clientId("host-client")
                .redirectUri(REDIRECT).scopes(Set.of(OAuthResourcePolicy.HOST_SCOPE)).state("expected-state")
                .additionalParameters(Map.of("code_challenge", challenge, "code_challenge_method", "S256")).build();
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                new LinearLiteUserDetails(7L, "user", true), "", List.of());
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client).id("authorization-id")
                .principalName("user").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of(OAuthResourcePolicy.HOST_SCOPE))
                .attribute(Principal.class.getName(), principal)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .token(new OAuth2AuthorizationCode(value, expiresAt.minusSeconds(60), expiresAt)).build();
        authorizations.save(authorization);
    }
}
