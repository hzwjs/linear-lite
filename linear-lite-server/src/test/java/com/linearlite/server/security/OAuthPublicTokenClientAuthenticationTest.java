package com.linearlite.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import static org.junit.jupiter.api.Assertions.*;

class OAuthPublicTokenClientAuthenticationTest {
    private OAuthPublicTokenClientAuthentication auth;

    @BeforeEach
    void setup() {
        RegisteredClient refreshClient = client("refresh-client", true);
        RegisteredClient codeOnlyClient = client("code-client", false);
        auth = new OAuthPublicTokenClientAuthentication(
                new InMemoryRegisteredClientRepository(refreshClient, codeOnlyClient));
    }

    @Test
    void converterAndProviderAreBoundToRefreshAndRevocationEndpoints() {
        MockHttpServletRequest refresh = refresh("refresh-client");
        Authentication request = auth.convert(refresh);
        assertTrue(auth.supports(request.getClass()));
        Authentication result = auth.authenticate(request);
        assertTrue(result.isAuthenticated());
        assertEquals("refresh-client", ((OAuth2ClientAuthenticationToken) result).getRegisteredClient().getClientId());
        assertFalse(auth.supports(OAuth2ClientAuthenticationToken.class));

        MockHttpServletRequest revoke = new MockHttpServletRequest("POST", "/oauth2/revoke");
        revoke.addParameter("client_id", "code-client");
        revoke.addParameter("token", "access-token");
        assertTrue(auth.authenticate(auth.convert(revoke)).isAuthenticated());
        assertNull(auth.convert(new MockHttpServletRequest("POST", "/api/projects")));
        assertNull(auth.convert(new MockHttpServletRequest("POST", "/oauth2/introspect")));
        assertNull(auth.convert(new MockHttpServletRequest("GET", "/oauth2/revoke")));
        assertThrows(OAuth2AuthenticationException.class, () -> auth.authenticate(auth.convert(refresh("code-client"))));
    }

    @Test
    void clientIdAloneDuplicateFieldsAndMixedCredentialsAreRejected() {
        MockHttpServletRequest noToken = new MockHttpServletRequest("POST", "/oauth2/token");
        noToken.addParameter("grant_type", "refresh_token");
        noToken.addParameter("client_id", "refresh-client");
        assertThrows(OAuth2AuthenticationException.class, () -> auth.convert(noToken));
        MockHttpServletRequest duplicated = refresh("refresh-client");
        duplicated.addParameter("client_id", "other-client");
        assertThrows(OAuth2AuthenticationException.class, () -> auth.convert(duplicated));
        MockHttpServletRequest mixed = refresh("refresh-client");
        mixed.addHeader("Authorization", "Basic abc");
        assertThrows(OAuth2AuthenticationException.class, () -> auth.convert(mixed));
        MockHttpServletRequest wrongClient = refresh("not-registered");
        assertThrows(OAuth2AuthenticationException.class, () -> auth.authenticate(auth.convert(wrongClient)));
    }

    private static MockHttpServletRequest refresh(String clientId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("grant_type", "refresh_token");
        request.addParameter("client_id", clientId);
        request.addParameter("refresh_token", "secret");
        return request;
    }

    private static RegisteredClient client(String id, boolean refresh) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id).clientId(id)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:33418/callback");
        if (refresh) builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        return builder.build();
    }
}
