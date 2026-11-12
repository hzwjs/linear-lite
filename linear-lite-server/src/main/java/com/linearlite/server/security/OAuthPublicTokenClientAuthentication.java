package com.linearlite.server.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Map;

/** 公共客户端声明 client_id；凭据有效性由后续 token provider 校验。 */
public final class OAuthPublicTokenClientAuthentication implements AuthenticationConverter, AuthenticationProvider {
    private final RegisteredClientRepository clients;

    public OAuthPublicTokenClientAuthentication(RegisteredClientRepository clients) { this.clients = clients; }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return null;
        boolean refresh = "/oauth2/token".equals(request.getRequestURI())
                && "refresh_token".equals(request.getParameter("grant_type"));
        boolean revoke = "/oauth2/revoke".equals(request.getRequestURI());
        if (!refresh && !revoke) return null;
        String clientId = single(request, "client_id");
        single(request, refresh ? "refresh_token" : "token");
        if (request.getHeader("Authorization") != null || request.getParameter("client_secret") != null
                || request.getParameter("client_assertion") != null
                || request.getParameter("client_assertion_type") != null
                || refresh && request.getParameterValues("grant_type").length != 1) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return new PublicTokenClient(clientId, refresh);
    }

    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || values[0].isBlank()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return values[0];
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        PublicTokenClient request = (PublicTokenClient) authentication;
        RegisteredClient client = clients.findByClientId((String) request.getPrincipal());
        if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || request.refresh && !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) { return PublicTokenClient.class.equals(authentication); }

    private static final class PublicTokenClient extends OAuth2ClientAuthenticationToken {
        private final boolean refresh;
        private PublicTokenClient(String clientId, boolean refresh) {
            super(clientId, ClientAuthenticationMethod.NONE, null, Map.of());
            this.refresh = refresh;
        }
    }
}
