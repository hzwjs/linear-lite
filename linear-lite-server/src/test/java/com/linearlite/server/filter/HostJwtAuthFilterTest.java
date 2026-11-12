package com.linearlite.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.security.OAuthResourcePolicy;
import com.linearlite.server.service.HostDeviceAuthorizationService;
import com.linearlite.server.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostJwtAuthFilterTest {
    private final JwtProperties jwtProperties = new JwtProperties();
    private final McpOAuthProperties oauth = new McpOAuthProperties();
    private final OAuth2AuthorizationService oauthService = mock(OAuth2AuthorizationService.class);
    private final HostDeviceAuthorizationService hosts = mock(HostDeviceAuthorizationService.class);
    private final HostDeviceAuthorization grant = new HostDeviceAuthorization();
    private JwtAuthFilter filter;

    @BeforeEach
    void setup() {
        oauth.setEnabled(true);
        oauth.setIssuer("https://example.test");
        oauth.setResource("https://example.test/mcp");
        grant.setId(12L);
        grant.setUserId(7L);
        grant.setDeviceId("a1111111-2222-4333-8444-555555555555");
        grant.setOauthAuthorizationId("auth-1");
        when(hosts.findByAuthorization("auth-1")).thenReturn(grant);
        @SuppressWarnings("unchecked") ObjectProvider<OAuth2AuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oauthService);
        filter = new JwtAuthFilter(new JwtUtil(jwtProperties), new ObjectMapper(), oauth, provider, hosts);
    }

    @Test
    void hostCredentialCannotAccessWebOrMcpAndBodyCannotChangeDevice() throws Exception {
        String token = token(7L, "auth-1", Instant.now().plusSeconds(300));
        when(oauthService.findByToken(eq(token), eq(OAuth2TokenType.ACCESS_TOKEN))).thenReturn(authorization(token));
        MockHttpServletRequest host = request("/api/collaboration-host/v3/heartbeat", token);
        host.setContent("{\"deviceId\":\"forged\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse ok = execute(host);
        assertEquals(200, ok.getStatus());
        assertEquals(7L, host.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID));
        assertEquals(grant.getDeviceId(), host.getAttribute(JwtAuthFilter.REQUEST_ATTR_HOST_DEVICE_ID));
        assertEquals(401, execute(request("/api/projects", token)).getStatus());
        assertEquals(401, execute(request("/mcp", token)).getStatus());
        assertEquals(401, execute(request("/api/remote-terminal/test", token)).getStatus());
    }

    @Test
    void revokedAndExpiredCredentialsAreDistinguishedAndRecoveryHasNoUserWriteIdentity() throws Exception {
        String activeToken = token(7L, "auth-1", Instant.now().plusSeconds(300));
        grant.setRevokedAt(LocalDateTime.now());
        MockHttpServletResponse revoked = execute(request("/api/collaboration-host/v3/heartbeat", activeToken));
        assertEquals(401, revoked.getStatus());
        assertTrue(revoked.getContentAsString().contains("已撤销"));
        String expiredToken = token(7L, "auth-1", Instant.now().minusSeconds(30));
        MockHttpServletResponse expired = execute(request("/api/collaboration-host/v3/heartbeat", expiredToken));
        assertEquals(401, expired.getStatus());
        assertTrue(expired.getContentAsString().contains("已过期"));
        MockHttpServletRequest recovery = request("/api/collaboration-host/v3/recovery/read", expiredToken);
        assertEquals(200, execute(recovery).getStatus());
        assertNull(recovery.getAttribute(JwtAuthFilter.REQUEST_ATTR_HOST_GRANT_ID));
        assertNull(recovery.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID));
        assertNull(recovery.getAttribute(JwtAuthFilter.REQUEST_ATTR_HOST_AUTHORIZATION_ID));
        assertEquals(401, execute(request("/api/collaboration-host/recovery/read", expiredToken)).getStatus());
    }

    @Test
    void signedHostIdentityForAnotherUserIsRejected() throws Exception {
        String token = token(8L, "auth-1", Instant.now().plusSeconds(300));
        assertEquals(401, execute(request("/api/collaboration-host/v3/heartbeat", token)).getStatus());
    }

    @Test
    void unsupportedUnsignedFutureAndBadSignatureTokensFailWithoutLeakingToken() throws Exception {
        String unsigned = Jwts.builder().subject("7").issuer(oauth.getIssuer())
                .expiration(Date.from(Instant.now().plusSeconds(60))).compact();
        String future = Jwts.builder().subject("7").issuer(oauth.getIssuer())
                .notBefore(Date.from(Instant.now().plusSeconds(60)))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
        String badSignature = Jwts.builder().subject("7").issuer(oauth.getIssuer())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor("another-long-enough-signing-key-which-is-not-the-app-key".getBytes(StandardCharsets.UTF_8))).compact();
        for (String invalid : List.of(unsigned, future, badSignature)) {
            for (String path : List.of("/api/collaboration-host/v3/heartbeat", "/api/collaboration-host/v3/batches/accept")) {
                MockHttpServletResponse response = execute(request(path, invalid));
                assertEquals(401, response.getStatus());
                assertFalse(response.getContentAsString().contains(invalid));
            }
        }
    }

    private String token(long userId, String authorizationId, Instant expiration) {
        return Jwts.builder().subject(Long.toString(userId)).issuer(oauth.getIssuer())
                .audience().add(OAuthResourcePolicy.hostResource(oauth)).and()
                .claim("scope", List.of(OAuthResourcePolicy.HOST_SCOPE))
                .claim(OAuthResourcePolicy.AUTHORIZATION_ID_CLAIM, authorizationId)
                .issuedAt(Date.from(Instant.now().minusSeconds(60))).expiration(Date.from(expiration))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
    }

    private OAuth2Authorization authorization(String token) {
        RegisteredClient client = RegisteredClient.withId("client-internal").clientId("client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:3000/callback").scope(OAuthResourcePolicy.HOST_SCOPE).build();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(300), Set.of(OAuthResourcePolicy.HOST_SCOPE));
        return OAuth2Authorization.withRegisteredClient(client).id("auth-1").principalName("user")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of(OAuthResourcePolicy.HOST_SCOPE)).accessToken(access).build();
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
