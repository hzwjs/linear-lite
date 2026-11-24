package com.linearlite.server.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** Revokes custom refresh-token families and their active access token. */
public final class McpOAuthTokenRevocationAuthenticationProvider implements AuthenticationProvider {

    private final McpOAuthRefreshTokenStore refreshTokenStore;
    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenRevocationAuthenticationProvider fallback;
    private final TransactionTemplate transactionTemplate;

    public McpOAuthTokenRevocationAuthenticationProvider(
            McpOAuthRefreshTokenStore refreshTokenStore,
            OAuth2AuthorizationService authorizationService,
            TransactionTemplate transactionTemplate) {
        this.refreshTokenStore = refreshTokenStore;
        this.authorizationService = authorizationService;
        this.fallback = new OAuth2TokenRevocationAuthenticationProvider(authorizationService);
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2TokenRevocationAuthenticationToken request = (OAuth2TokenRevocationAuthenticationToken) authentication;
        if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientPrincipal)
                || !clientPrincipal.isAuthenticated() || clientPrincipal.getRegisteredClient() == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        RegisteredClient client = clientPrincipal.getRegisteredClient();
        McpOAuthRefreshTokenStore.Revocation revocation = transactionTemplate.execute(status -> {
            McpOAuthRefreshTokenStore.Revocation result = refreshTokenStore.revoke(
                    request.getToken(), client.getId(), Instant.now());
            if (result == null) {
                return null;
            }
            OAuth2Authorization authorization = authorizationService.findById(result.authorizationId());
            if (authorization != null && client.getId().equals(authorization.getRegisteredClientId())) {
                OAuth2Authorization.Token<org.springframework.security.oauth2.core.OAuth2AccessToken> accessToken =
                        authorization.getAccessToken();
                if (accessToken != null && !accessToken.isInvalidated()) {
                    OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
                    builder.token(accessToken.getToken(), metadata -> metadata.put(
                            OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true));
                    authorizationService.save(builder.build());
                }
            }
            return result;
        });
        if (revocation == null) {
            return fallback.authenticate(authentication);
        }
        return new OAuth2TokenRevocationAuthenticationToken(
                new OAuth2RefreshToken(request.getToken(), Instant.now()), clientPrincipal);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
