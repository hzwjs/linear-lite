package com.linearlite.server.config;

import com.linearlite.server.security.LinearLiteUserDetails;
import com.linearlite.server.security.OAuthResourcePolicy;
import com.linearlite.server.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HostOAuthTokenCustomizerTest {
    @Test
    void authorizationAndRefreshTokensBindTheSameGrantAndHostAudience() {
        McpOAuthProperties properties = new McpOAuthProperties();
        properties.setIssuer("https://example.test");
        properties.setResource("https://example.test/mcp");
        JwtProperties keys = new JwtProperties();
        McpOAuthServerConfiguration configuration = new McpOAuthServerConfiguration();
        OAuth2TokenGenerator<? extends OAuth2Token> generator = configuration.mcpOAuthTokenGenerator(
                configuration.mcpOAuthJwtEncoder(keys), configuration.mcpOAuthTokenCustomizer(properties));
        RegisteredClient client = RegisteredClient.withId("client").clientId("client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:1234/callback").scope(OAuthResourcePolicy.HOST_SCOPE).build();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client).id("auth-id")
                .principalName("user").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of(OAuthResourcePolicy.HOST_SCOPE)).build();
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                new LinearLiteUserDetails(7L, "user", true), "", List.of());
        JwtUtil parser = new JwtUtil(keys);

        for (AuthorizationGrantType type : List.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN)) {
            var context = DefaultOAuth2TokenContext.builder().registeredClient(client).principal(principal)
                    .authorization(authorization).authorizationServerContext(mock(AuthorizationServerContext.class))
                    .authorizedScopes(Set.of(OAuthResourcePolicy.HOST_SCOPE)).authorizationGrantType(type)
                    .tokenType(OAuth2TokenType.ACCESS_TOKEN).build();
            OAuth2Token token = generator.generate(context);
            assertNotNull(token);
            var claims = parser.parseToken(token.getTokenValue());
            assertEquals(Set.of(OAuthResourcePolicy.hostResource(properties)), claims.getAudience());
            assertEquals("auth-id", claims.get(OAuthResourcePolicy.AUTHORIZATION_ID_CLAIM));
            assertEquals("7", claims.getSubject());
        }
    }
}
