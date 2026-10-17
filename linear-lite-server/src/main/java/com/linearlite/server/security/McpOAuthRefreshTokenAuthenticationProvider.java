package com.linearlite.server.security;

import com.linearlite.server.config.McpOAuthProperties;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Set;

/** Rotates hashed refresh-token families without writing refresh-token values to OAuth authorization rows. */
public final class McpOAuthRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    private final OAuth2AuthorizationService authorizationService;
    private final McpOAuthRefreshTokenStore refreshTokenStore;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;
    private final McpOAuthProperties properties;
    private final TransactionTemplate transactionTemplate;

    public McpOAuthRefreshTokenAuthenticationProvider(
            OAuth2AuthorizationService authorizationService,
            McpOAuthRefreshTokenStore refreshTokenStore,
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
            McpOAuthProperties properties,
            TransactionTemplate transactionTemplate) {
        this.authorizationService = authorizationService;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2RefreshTokenAuthenticationToken tokenRequest = (OAuth2RefreshTokenAuthenticationToken) authentication;
        if (!(tokenRequest.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientPrincipal)
                || !clientPrincipal.isAuthenticated() || clientPrincipal.getRegisteredClient() == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        RegisteredClient client = clientPrincipal.getRegisteredClient();
        if (!client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
        }
        Object requestedResource = tokenRequest.getAdditionalParameters().get("resource");
        if (requestedResource != null && !properties.getResource().equals(requestedResource)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "invalid_target", "The requested resource does not match Linear Lite MCP.", null));
        }

        Instant now = Instant.now();
        Duration ttl = Duration.ofSeconds(properties.getRefreshTokenTtlSeconds());
        EndpointResult result = transactionTemplate.execute(status -> {
            McpOAuthRefreshTokenStore.Rotation rotation = refreshTokenStore.rotate(
                    tokenRequest.getRefreshToken(),
                    client.getId(),
                    properties.getResource(),
                    tokenRequest.getScopes(),
                    now,
                    ttl);
            if (rotation.status() == McpOAuthRefreshTokenStore.Status.REPLAYED) {
                return EndpointResult.replayed();
            }
            if (rotation.status() == McpOAuthRefreshTokenStore.Status.INVALID_SCOPE) {
                return EndpointResult.invalidScope();
            }
            if (rotation.status() != McpOAuthRefreshTokenStore.Status.SUCCESS) {
                return EndpointResult.invalidGrant();
            }

            OAuth2Authorization authorization = authorizationService.findById(rotation.authorizationId());
            if (authorization == null || !client.getId().equals(authorization.getRegisteredClientId())) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            Set<String> authorizedScopes = authorization.getAuthorizedScopes();
            if (!authorizedScopes.containsAll(rotation.scopes())) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
            }
            Authentication userPrincipal = authorization.getAttribute(java.security.Principal.class.getName());
            if (userPrincipal == null
                    || !(userPrincipal.getPrincipal() instanceof LinearLiteUserDetails)) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }

            DefaultOAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                    .registeredClient(client)
                    .principal(userPrincipal)
                    .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                    .authorization(authorization)
                    .authorizedScopes(rotation.scopes())
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .authorizationGrant(tokenRequest)
                    .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                    .build();
            OAuth2Token generatedAccessToken = tokenGenerator.generate(tokenContext);
            if (generatedAccessToken == null) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
            }
            OAuth2AccessToken accessToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    generatedAccessToken.getTokenValue(),
                    generatedAccessToken.getIssuedAt(),
                    generatedAccessToken.getExpiresAt(),
                    rotation.scopes());

            OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization.from(authorization);
            if (generatedAccessToken instanceof ClaimAccessor claimAccessor) {
                authorizationBuilder.token(accessToken, metadata -> {
                    metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claimAccessor.getClaims());
                    metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false);
                });
            } else {
                authorizationBuilder.accessToken(accessToken);
            }
            authorizationService.save(authorizationBuilder.build());

            return EndpointResult.success(new OAuth2AccessTokenAuthenticationToken(
                    client, clientPrincipal, accessToken, rotation.refreshToken(), new HashMap<>()));
        });

        if (result == null || result.status() == EndpointStatus.INVALID_GRANT
                || result.status() == EndpointStatus.REPLAYED) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
        if (result.status() == EndpointStatus.INVALID_SCOPE) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
        }
        return result.authentication();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private enum EndpointStatus {
        SUCCESS,
        INVALID_GRANT,
        INVALID_SCOPE,
        REPLAYED
    }

    private record EndpointResult(EndpointStatus status, Authentication authentication) {
        private static EndpointResult success(Authentication authentication) {
            return new EndpointResult(EndpointStatus.SUCCESS, authentication);
        }

        private static EndpointResult invalidGrant() {
            return new EndpointResult(EndpointStatus.INVALID_GRANT, null);
        }

        private static EndpointResult invalidScope() {
            return new EndpointResult(EndpointStatus.INVALID_SCOPE, null);
        }

        private static EndpointResult replayed() {
            return new EndpointResult(EndpointStatus.REPLAYED, null);
        }
    }
}
