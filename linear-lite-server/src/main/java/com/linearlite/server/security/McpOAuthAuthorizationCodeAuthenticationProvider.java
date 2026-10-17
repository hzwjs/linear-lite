package com.linearlite.server.security;

import com.linearlite.server.config.McpOAuthProperties;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/** Adds hashed refresh-token issuance around SAS's existing authorization-code validation. */
public final class McpOAuthAuthorizationCodeAuthenticationProvider implements AuthenticationProvider {

    private final OAuth2AuthorizationCodeAuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizationService;
    private final McpOAuthRefreshTokenStore refreshTokenStore;
    private final McpOAuthProperties properties;
    private final TransactionTemplate transactionTemplate;

    public McpOAuthAuthorizationCodeAuthenticationProvider(
            OAuth2AuthorizationCodeAuthenticationProvider delegate,
            OAuth2AuthorizationService authorizationService,
            McpOAuthRefreshTokenStore refreshTokenStore,
            McpOAuthProperties properties,
            TransactionTemplate transactionTemplate) {
        this.delegate = delegate;
        this.authorizationService = authorizationService;
        this.refreshTokenStore = refreshTokenStore;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        return transactionTemplate.execute(status -> {
            OAuth2AccessTokenAuthenticationToken issued =
                    (OAuth2AccessTokenAuthenticationToken) delegate.authenticate(authentication);
            if (!issued.getRegisteredClient().getAuthorizationGrantTypes()
                    .contains(AuthorizationGrantType.REFRESH_TOKEN)) {
                return issued;
            }

            OAuth2Authorization authorization = authorizationService.findByToken(
                    issued.getAccessToken().getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
            if (authorization == null) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
            }

            Instant issuedAt = Instant.now();
            Duration ttl = Duration.ofSeconds(properties.getRefreshTokenTtlSeconds());
            OAuth2RefreshToken refreshToken = refreshTokenStore.issue(
                    authorization.getId(),
                    issued.getRegisteredClient().getId(),
                    properties.getResource(),
                    authorization.getAuthorizedScopes(),
                    issuedAt,
                    ttl);
            return new OAuth2AccessTokenAuthenticationToken(
                    issued.getRegisteredClient(),
                    (Authentication) issued.getPrincipal(),
                    issued.getAccessToken(),
                    refreshToken,
                    issued.getAdditionalParameters());
        });
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return org.springframework.security.oauth2.server.authorization.authentication
                .OAuth2AuthorizationCodeAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
