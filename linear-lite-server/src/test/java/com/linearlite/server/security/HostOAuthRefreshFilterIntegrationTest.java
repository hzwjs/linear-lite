package com.linearlite.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.service.HostDeviceAuthorizationService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.web.OAuth2ClientAuthenticationFilter;
import org.springframework.security.oauth2.server.authorization.web.OAuth2TokenEndpointFilter;
import org.springframework.security.oauth2.server.authorization.web.OAuth2TokenRevocationEndpointFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SAS 客户端过滤器与 token provider；JDBC 为内存假对象，不访问数据库。 */
class HostOAuthRefreshFilterIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String resource = "https://example.test/api/collaboration-host";
    private FakeJdbc fake;
    private McpOAuthRefreshTokenStore store;
    private HostDeviceAuthorizationService hosts;
    private OAuth2ClientAuthenticationFilter clientFilter;
    private OAuth2TokenEndpointFilter tokenFilter;
    private OAuth2TokenRevocationEndpointFilter revokeFilter;
    private String refreshToken;

    @BeforeEach
    void setup() {
        AuthorizationServerContextHolder.setContext(mock(AuthorizationServerContext.class));
        RegisteredClient owner = client("owner");
        RegisteredClient other = client("other");
        var clients = new InMemoryRegisteredClientRepository(owner, other);
        var oauth = new McpOAuthProperties();
        oauth.setIssuer("https://example.test");
        oauth.setResource("https://example.test/mcp");
        var authorizations = new InMemoryOAuth2AuthorizationService();
        Authentication user = UsernamePasswordAuthenticationToken.authenticated(
                new LinearLiteUserDetails(7L, "user", true), "", List.of());
        authorizations.save(OAuth2Authorization.withRegisteredClient(owner).id("grant-auth-id").principalName("user")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of(OAuthResourcePolicy.HOST_SCOPE))
                .attribute(Principal.class.getName(), user).build());
        fake = new FakeJdbc();
        store = new McpOAuthRefreshTokenStore(fake.jdbc);
        refreshToken = store.issue("grant-auth-id", owner.getId(), resource,
                Set.of(OAuthResourcePolicy.HOST_SCOPE), Instant.now(), Duration.ofHours(1)).getTokenValue();
        hosts = mock(HostDeviceAuthorizationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AtomicInteger tokenSequence = new AtomicInteger();
        OAuth2TokenGenerator<OAuth2Token> generator = context -> OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                ? new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-" + tokenSequence.incrementAndGet(),
                    Instant.now(), Instant.now().plusSeconds(300), context.getAuthorizedScopes()) : null;
        var refresh = new McpOAuthRefreshTokenAuthenticationProvider(authorizations, store, generator, oauth, transaction, hosts);
        var revoke = new McpOAuthTokenRevocationAuthenticationProvider(store, authorizations, transaction, hosts);
        var publicClient = new OAuthPublicTokenClientAuthentication(clients);
        clientFilter = new OAuth2ClientAuthenticationFilter(new ProviderManager(publicClient), req -> true);
        clientFilter.setAuthenticationConverter(publicClient);
        tokenFilter = new OAuth2TokenEndpointFilter(new ProviderManager(refresh));
        revokeFilter = new OAuth2TokenRevocationEndpointFilter(new ProviderManager(revoke));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void actualFiltersEnforceTokenClientResourceAndReplayBeforeRotating() throws Exception {
        assertEquals(400, exchange("other", refreshToken, resource).getStatus());
        assertEquals(400, exchange("owner", refreshToken, "https://example.test/mcp").getStatus());
        assertEquals(400, exchange("owner", "unknown-token", resource).getStatus());
        MockHttpServletResponse success = exchange("owner", refreshToken, resource);
        assertEquals(200, success.getStatus(), success.getContentAsString());
        String rotated = json.readTree(success.getContentAsString()).get("refresh_token").asText();
        assertNotEquals(refreshToken, rotated);
        assertEquals(400, exchange("owner", refreshToken, resource).getStatus());
        assertEquals(400, exchange("owner", rotated, resource).getStatus());
        verify(hosts, times(1)).requireActiveAuthorization("grant-auth-id", 7L);
    }

    @Test
    void actualRevocationFilterRequiresTokenOwnershipAndRevokesFamily() throws Exception {
        assertEquals(200, revoke("other", refreshToken).getStatus());
        verify(hosts, never()).revokeAuthorization(anyString());
        assertEquals(200, revoke("owner", refreshToken).getStatus());
        verify(hosts).revokeAuthorization("grant-auth-id");
        assertEquals(400, exchange("owner", refreshToken, resource).getStatus());
    }

    private MockHttpServletResponse exchange(String client, String token, String target) throws Exception {
        MockHttpServletRequest request = request("/oauth2/token");
        request.addParameter("grant_type", "refresh_token");
        request.addParameter("client_id", client);
        request.addParameter("refresh_token", token);
        request.addParameter("resource", target);
        return execute(request, tokenFilter);
    }

    private MockHttpServletResponse revoke(String client, String token) throws Exception {
        MockHttpServletRequest request = request("/oauth2/revoke");
        request.addParameter("client_id", client);
        request.addParameter("token", token);
        return execute(request, revokeFilter);
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request, Filter endpoint) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(new HttpServlet() {}, clientFilter, endpoint).doFilter(request, response);
        return response;
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setServletPath(uri);
        request.setContentType("application/x-www-form-urlencoded");
        return request;
    }

    private static RegisteredClient client(String id) {
        return RegisteredClient.withId(id).clientId(id).clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:1234/callback").scope(OAuthResourcePolicy.HOST_SCOPE).build();
    }

    private static final class FakeJdbc {
        final JdbcOperations jdbc = mock(JdbcOperations.class);
        final Map<String, Map<String, Object>> families = new HashMap<>();
        final Map<String, Map<String, Object>> tokens = new HashMap<>();

        @SuppressWarnings({"rawtypes", "unchecked"})
        FakeJdbc() {
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                RowMapper mapper = invocation.getArgument(1);
                Object[] args = (Object[]) invocation.getRawArguments()[2];
                Map<String, Object> row;
                if (sql.startsWith("SELECT family_id FROM")) {
                    row = tokens.get(args[0]);
                    return row == null ? List.of() : List.of(row.get("family_id"));
                } else if (sql.contains("JOIN mcp_oauth_refresh_token")) {
                    Map<String, Object> token = tokens.get(args[0]);
                    row = token == null ? null : families.get(token.get("family_id"));
                } else if (sql.contains("FROM mcp_oauth_refresh_token_family")) {
                    row = families.get(args[0]);
                } else {
                    row = tokens.get(args[0]);
                }
                if (row == null) return List.of();
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(i -> row.get(i.getArgument(0)));
                when(rs.getTimestamp(anyString())).thenAnswer(i -> row.get(i.getArgument(0)));
                return List.of(mapper.mapRow(rs, 0));
            }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                Object[] p = (Object[]) invocation.getRawArguments()[1];
                if (sql.contains("INSERT INTO mcp_oauth_refresh_token_family")) {
                    Map<String, Object> row = new HashMap<>();
                    String[] keys = {"family_id", "authorization_id", "registered_client_id", "resource", "scopes", "created_at", "expires_at"};
                    for (int i = 0; i < keys.length; i++) row.put(keys[i], p[i]);
                    families.put((String) p[0], row);
                } else if (sql.contains("INSERT INTO mcp_oauth_refresh_token")) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("family_id", p[1]); row.put("expires_at", p[3]);
                    tokens.put((String) p[0], row);
                } else if (sql.contains("SET consumed_at = ?")) {
                    Map<String, Object> row = tokens.get(p[1]);
                    if (row == null || row.get("consumed_at") != null || row.get("revoked_at") != null) return 0;
                    row.put("consumed_at", p[0]);
                } else if (sql.contains("SET expires_at = ?")) {
                    families.get(p[2]).put("expires_at", p[0]);
                } else if (sql.contains("UPDATE mcp_oauth_refresh_token_family SET revoked_at")) {
                    families.get(p[1]).put("revoked_at", p[0]);
                } else if (sql.contains("UPDATE mcp_oauth_refresh_token SET revoked_at")) {
                    tokens.values().stream().filter(row -> p[1].equals(row.get("family_id")))
                            .forEach(row -> row.put("revoked_at", p[0]));
                }
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
        }
    }
}
