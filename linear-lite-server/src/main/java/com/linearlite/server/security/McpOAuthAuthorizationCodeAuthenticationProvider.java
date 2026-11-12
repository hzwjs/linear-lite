package com.linearlite.server.security;

import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.service.HostDeviceAuthorizationService;
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
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
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
    private final HostDeviceAuthorizationService hostAuthorizations;

    public McpOAuthAuthorizationCodeAuthenticationProvider(
            OAuth2AuthorizationCodeAuthenticationProvider delegate,
            OAuth2AuthorizationService authorizationService,
            McpOAuthRefreshTokenStore refreshTokenStore,
            McpOAuthProperties properties,
            TransactionTemplate transactionTemplate,
            HostDeviceAuthorizationService hostAuthorizations) {
        this.delegate = delegate;
        this.authorizationService = authorizationService;
        this.refreshTokenStore = refreshTokenStore;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.hostAuthorizations = hostAuthorizations;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        return transactionTemplate.execute(status -> {
            OAuth2AuthorizationCodeAuthenticationToken request = (OAuth2AuthorizationCodeAuthenticationToken) authentication;
            OAuth2Authorization original = authorizationService.findByToken(request.getCode(), new OAuth2TokenType("code"));
            if (original == null) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            String resource;
            try {
                resource = OAuthResourcePolicy.resourceFor(original.getAuthorizedScopes(), properties);
            } catch (IllegalArgumentException exception) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
            }
            if (!resource.equals(request.getAdditionalParameters().get("resource"))) {
                throw new OAuth2AuthenticationException("invalid_target");
            }
            OAuth2AccessTokenAuthenticationToken issued =
                    (OAuth2AccessTokenAuthenticationToken) delegate.authenticate(authentication);
            OAuth2Authorization authorization = authorizationService.findByToken(
                    issued.getAccessToken().getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
            if (authorization == null) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
            }
            if (OAuthResourcePolicy.isHost(authorization.getAuthorizedScopes())) {
                Authentication user = authorization.getAttribute(java.security.Principal.class.getName());
                if (user == null || !(user.getPrincipal() instanceof LinearLiteUserDetails details)) {
                    throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                }
                String deviceId = issued.getRegisteredClient().getClientSettings().getSetting(OAuthResourcePolicy.DEVICE_ID_SETTING);
                String deviceName = issued.getRegisteredClient().getClientSettings().getSetting(OAuthResourcePolicy.DEVICE_NAME_SETTING);
                hostAuthorizations.register(authorization.getId(), details.getUserId(), deviceId, deviceName);
            }
            if (!issued.getRegisteredClient().getAuthorizationGrantTypes()
                    .contains(AuthorizationGrantType.REFRESH_TOKEN)) {
                return issued;
            }

            Instant issuedAt = Instant.now();
            Duration ttl = Duration.ofSeconds(properties.getRefreshTokenTtlSeconds());
            OAuth2RefreshToken refreshToken = refreshTokenStore.issue(
                    authorization.getId(),
                    issued.getRegisteredClient().getId(),
                    resource,
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
