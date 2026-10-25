package com.linearlite.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.security.McpOAuthRedirectUris;
import com.linearlite.server.security.LinearLiteAuthenticationProvider;
import com.linearlite.server.security.LinearLiteUserDetails;
import com.linearlite.server.security.McpOAuthAuthorizationCodeAuthenticationProvider;
import com.linearlite.server.security.McpOAuthRefreshTokenAuthenticationProvider;
import com.linearlite.server.security.McpOAuthRefreshTokenStore;
import com.linearlite.server.security.McpOAuthTokenRevocationAuthenticationProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.jackson2.SecurityJackson2Modules;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.jsonwebtoken.security.Keys;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpOAuthProperties.class)
public class McpOAuthServerConfiguration {

    private static final String ACCESS_TOKEN_KEY_ID = "linear-lite-mcp-oauth";

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    SecurityFilterChain mcpOAuthAuthorizationServerSecurityFilterChain(
            HttpSecurity http,
            McpProperties mcpProperties,
            McpOAuthProperties properties,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            OAuth2AuthorizationConsentService consentService,
            AuthorizationServerSettings authorizationServerSettings,
            OAuth2TokenGenerator<?> tokenGenerator,
            McpOAuthRefreshTokenStore refreshTokenStore,
            PlatformTransactionManager transactionManager,
            McpOAuthProperties oauthProperties) throws Exception {
        if (!mcpProperties.isEnabled()) {
            throw new IllegalStateException("mcp.enabled must be true when MCP OAuth is enabled.");
        }
        properties.validateForAuthorizationServer();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        .registeredClientRepository(registeredClientRepository)
                        .authorizationService(authorizationService)
                        .authorizationConsentService(consentService)
                        .authorizationServerSettings(authorizationServerSettings)
                        .tokenGenerator(tokenGenerator)
                        .authorizationEndpoint(endpoint -> endpoint
                                .consentPage("/oauth2/consent")
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
                                        codeProvider.setAuthenticationValidator(context -> {
                                            String requested = ((OAuth2AuthorizationCodeRequestAuthenticationToken)
                                                    context.getAuthentication()).getRedirectUri();
                                            if (!McpOAuthRedirectUris.matches(
                                                    requested, context.getRegisteredClient().getRedirectUris())) {
                                                throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                                                        new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
                                                                "Invalid redirect_uri.", null), null);
                                            }
                                            OAuth2AuthorizationCodeRequestAuthenticationValidator
                                                    .DEFAULT_SCOPE_VALIDATOR.accept(context);
                                        });
                                    }
                                })))
                        .tokenEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                            for (int index = 0; index < providers.size(); index++) {
                                if (providers.get(index) instanceof OAuth2AuthorizationCodeAuthenticationProvider codeProvider) {
                                    providers.set(index, new McpOAuthAuthorizationCodeAuthenticationProvider(
                                            codeProvider, authorizationService, refreshTokenStore, oauthProperties,
                                            transactionTemplate));
                                }
                            }
                            providers.removeIf(OAuth2RefreshTokenAuthenticationProvider.class::isInstance);
                            providers.add(new McpOAuthRefreshTokenAuthenticationProvider(
                                    authorizationService, refreshTokenStore, tokenGenerator, oauthProperties,
                                    transactionTemplate));
                        }))
                        .tokenRevocationEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                            providers.removeIf(OAuth2TokenRevocationAuthenticationProvider.class::isInstance);
                            providers.add(0, new McpOAuthTokenRevocationAuthenticationProvider(
                                    refreshTokenStore, authorizationService, transactionTemplate));
                        }))
                        .authorizationServerMetadataEndpoint(endpoint -> endpoint
                                .authorizationServerMetadataCustomizer(metadata -> {
                                    metadata.claim("registration_endpoint", properties.getIssuer() + "/oauth2/register");
                                    metadata.claim("token_endpoint_auth_methods_supported", List.of("none"));
                                    metadata.claim("code_challenge_methods_supported", List.of("S256"));
                                    metadata.claim("scopes_supported", List.of("linear-lite:read", "linear-lite:write"));
                                    metadata.claim("grant_types_supported", List.of("authorization_code", "refresh_token"));
                                    metadata.claim("revocation_endpoint_auth_methods_supported", List.of("none"));
                                    metadata.claims(claims -> {
                                        claims.remove("jwks_uri");
                                        claims.remove("introspection_endpoint");
                                        claims.remove("introspection_endpoint_auth_methods_supported");
                                    });
                                })))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServerConfigurer.getEndpointsMatcher()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            LinearLiteAuthenticationProvider authenticationProvider) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/oauth2/consent").authenticated()
                        .requestMatchers("/oauth/login").permitAll()
                        .anyRequest().permitAll())
                .authenticationProvider(authenticationProvider)
                .formLogin(form -> form.loginPage("/oauth/login")
                        .loginProcessingUrl("/oauth/login")
                        .usernameParameter("identity")
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/oauth/logout"))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/mcp", "/oauth2/register", "/internal/collaboration/**"));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    RegisteredClientRepository mcpOAuthRegisteredClientRepository(JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    OAuth2AuthorizationService mcpOAuthAuthorizationService(
            JdbcOperations jdbcOperations,
            RegisteredClientRepository registeredClientRepository) {
        JdbcOAuth2AuthorizationService authorizationService =
                new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
        ObjectMapper objectMapper = mcpOAuthAuthorizationObjectMapper();

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
        rowMapper.setObjectMapper(objectMapper);
        authorizationService.setAuthorizationRowMapper(rowMapper);

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parametersMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
        parametersMapper.setObjectMapper(objectMapper);
        authorizationService.setAuthorizationParametersMapper(parametersMapper);
        return authorizationService;
    }

    static ObjectMapper mcpOAuthAuthorizationObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModules(SecurityJackson2Modules.getModules(
                JdbcOAuth2AuthorizationService.class.getClassLoader()));
        objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
        objectMapper.addMixIn(LinearLiteUserDetails.class, LinearLiteUserDetailsMixin.class);
        return objectMapper;
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    OAuth2AuthorizationConsentService mcpOAuthConsentService(
            JdbcOperations jdbcOperations,
            RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    McpOAuthRefreshTokenStore mcpOAuthRefreshTokenStore(JdbcOperations jdbcOperations) {
        return new McpOAuthRefreshTokenStore(jdbcOperations);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    AuthorizationServerSettings mcpAuthorizationServerSettings(McpOAuthProperties properties) {
        properties.validateForAuthorizationServer();
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .authorizationEndpoint("/oauth2/authorize")
                .tokenEndpoint("/oauth2/token")
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    JwtEncoder mcpOAuthJwtEncoder(JwtProperties jwtProperties) {
        SecretKey secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        OctetSequenceKey signingKey = new OctetSequenceKey.Builder(secretKey)
                .keyID(ACCESS_TOKEN_KEY_ID)
                .algorithm(JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> keySource = (selector, context) -> selector.select(new JWKSet(signingKey));
        return new NimbusJwtEncoder(keySource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    OAuth2TokenCustomizer<JwtEncodingContext> mcpOAuthTokenCustomizer(McpOAuthProperties properties) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            Object principal = context.getPrincipal().getPrincipal();
            if (!(principal instanceof LinearLiteUserDetails user)) {
                throw new IllegalStateException("OAuth user identity is unavailable.");
            }
            context.getJwsHeader()
                    .algorithm(MacAlgorithm.HS256)
                    .keyId(ACCESS_TOKEN_KEY_ID);
            context.getClaims()
                    .subject(String.valueOf(user.getUserId()))
                    .issuer(properties.getIssuer())
                    .audience(new ArrayList<>(List.of(properties.getResource())))
                    .claim("scope", new ArrayList<>(context.getAuthorizedScopes()));
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
    OAuth2TokenGenerator<? extends OAuth2Token> mcpOAuthTokenGenerator(
            JwtEncoder jwtEncoder,
            OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(tokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(jwtGenerator);
    }
}
