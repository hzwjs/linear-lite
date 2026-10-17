package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.security.McpOAuthRedirectUris;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** RFC 7591 registration for public authorization-code clients using PKCE. */
@RestController
@ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
public class McpOAuthClientRegistrationController {

    private static final Set<String> SCOPES = Set.of("linear-lite:read", "linear-lite:write");

    private final RegisteredClientRepository clients;
    private final McpOAuthProperties properties;

    public McpOAuthClientRegistrationController(RegisteredClientRepository clients, McpOAuthProperties properties) {
        this.clients = clients;
        this.properties = properties;
    }

    @PostMapping(path = "/oauth2/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody JsonNode request) {
        if (request == null || !request.isObject()) {
            return invalid("Client metadata must be a JSON object.");
        }
        List<String> redirectUris = stringArray(request.path("redirect_uris"));
        if (redirectUris.isEmpty() || redirectUris.size() > 5
                || !McpOAuthRedirectUris.isAllowedRegistrationSet(new HashSet<>(redirectUris))
                || redirectUris.stream().mapToInt(String::length).sum() + redirectUris.size() > 1000
        ) {
            return invalid("Unsupported redirect_uris.");
        }
        List<String> grantTypes = request.path("grant_types").isMissingNode()
                ? List.of("authorization_code") : stringArray(request.path("grant_types"));
        if (!grantTypes.contains("authorization_code") || grantTypes.size() > 2
                || grantTypes.stream().anyMatch(value -> !Set.of("authorization_code", "refresh_token").contains(value))
                || grantTypes.stream().distinct().count() != grantTypes.size()
                || !matchesOptionalArray(request, "response_types", "code")
                || !matchesOptionalString(request, "token_endpoint_auth_method", "none")) {
            return invalid("Only authorization code, optional refresh token, and public clients are supported.");
        }
        JsonNode scopeNode = request.path("scope");
        if (!scopeNode.isMissingNode() && !scopeNode.isTextual()) {
            return invalid("Invalid scope.");
        }
        String scope = scopeNode.isMissingNode() ? "linear-lite:read linear-lite:write" : scopeNode.textValue();
        List<String> requestedScopes = List.of(scope.trim().split("\\s+"));
        if (requestedScopes.isEmpty() || requestedScopes.stream().anyMatch(value -> !SCOPES.contains(value))) {
            return invalid("Unsupported scope.");
        }
        JsonNode nameNode = request.path("client_name");
        if (!nameNode.isMissingNode() && !nameNode.isTextual()) {
            return invalid("Invalid client_name.");
        }
        String clientName = nameNode.isMissingNode() ? "Codex" : nameNode.textValue().trim();
        if (clientName.isEmpty() || clientName.length() > 128) {
            return invalid("Invalid client_name.");
        }
        String clientId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        List<org.springframework.security.oauth2.core.AuthorizationGrantType> authorizationGrantTypes =
                new ArrayList<>(List.of(AuthorizationGrantType.AUTHORIZATION_CODE));
        if (grantTypes.contains("refresh_token")) {
            authorizationGrantTypes.add(AuthorizationGrantType.REFRESH_TOKEN);
        }
        TokenSettings.Builder tokenSettings = TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofSeconds(properties.getTokenTtlSeconds()));
        if (grantTypes.contains("refresh_token")) {
            tokenSettings.refreshTokenTimeToLive(Duration.ofSeconds(properties.getRefreshTokenTtlSeconds()))
                    .reuseRefreshTokens(false);
        }
        RegisteredClient client = RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientIdIssuedAt(issuedAt)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantTypes(grants -> grants.addAll(authorizationGrantTypes))
                .redirectUris(uris -> uris.addAll(redirectUris))
                .scopes(scopes -> scopes.addAll(requestedScopes))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(tokenSettings.build())
                .build();
        clients.save(client);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", issuedAt.getEpochSecond());
        response.put("client_name", clientName);
        response.put("redirect_uris", redirectUris);
        response.put("application_type", "http".equals(URI.create(redirectUris.get(0)).getScheme())
                ? "native" : "web");
        response.put("grant_types", grantTypes);
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "none");
        response.put("scope", String.join(" ", requestedScopes));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private static ResponseEntity<Map<String, Object>> invalid(String description) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_client_metadata", "error_description", description));
    }

    private static List<String> stringArray(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                return List.of();
            }
            result.add(item.textValue());
        }
        return result;
    }

    private static boolean matchesOptionalArray(JsonNode request, String field, String expected) {
        JsonNode value = request.path(field);
        return value.isMissingNode() || stringArray(value).equals(List.of(expected));
    }

    private static boolean matchesOptionalString(JsonNode request, String field, String expected) {
        JsonNode value = request.path(field);
        return value.isMissingNode() || value.isTextual() && expected.equals(value.textValue());
    }
}
