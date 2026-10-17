package com.linearlite.server.security;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stores refresh-token hashes outside the legacy OAuth authorization row. */
public final class McpOAuthRefreshTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RowMapper<Family> FAMILY_MAPPER = (resultSet, rowNum) -> new Family(
            resultSet.getString("family_id"),
            resultSet.getString("authorization_id"),
            resultSet.getString("registered_client_id"),
            resultSet.getString("resource"),
            resultSet.getString("scopes"),
            resultSet.getTimestamp("expires_at").toInstant(),
            resultSet.getTimestamp("revoked_at") == null ? null : resultSet.getTimestamp("revoked_at").toInstant());

    private static final RowMapper<TokenRow> TOKEN_MAPPER = (resultSet, rowNum) -> new TokenRow(
            resultSet.getString("family_id"),
            resultSet.getTimestamp("expires_at").toInstant(),
            resultSet.getTimestamp("consumed_at") == null ? null : resultSet.getTimestamp("consumed_at").toInstant(),
            resultSet.getTimestamp("revoked_at") == null ? null : resultSet.getTimestamp("revoked_at").toInstant());

    private final JdbcOperations jdbc;

    public McpOAuthRefreshTokenStore(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    public OAuth2RefreshToken issue(String authorizationId, String registeredClientId, String resource,
                                    Set<String> scopes, Instant issuedAt, Duration ttl) {
        String familyId = UUID.randomUUID().toString();
        String tokenValue = newTokenValue();
        Instant expiresAt = issuedAt.plus(ttl);
        jdbc.update("""
                        INSERT INTO mcp_oauth_refresh_token_family
                            (family_id, authorization_id, registered_client_id, resource, scopes,
                             created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                familyId, authorizationId, registeredClientId, resource, String.join(" ", scopes),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        jdbc.update("""
                        INSERT INTO mcp_oauth_refresh_token
                            (token_hash, family_id, issued_at, expires_at)
                        VALUES (?, ?, ?, ?)
                        """,
                hash(tokenValue), familyId, Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        return new OAuth2RefreshToken(tokenValue, issuedAt, expiresAt);
    }

    public Rotation rotate(String presentedToken, String registeredClientId, String resource,
                           Set<String> requestedScopes, Instant issuedAt, Duration ttl) {
        String presentedHash = hash(presentedToken);
        List<String> familyIds = jdbc.query(
                "SELECT family_id FROM mcp_oauth_refresh_token WHERE token_hash = ?",
                (resultSet, rowNum) -> resultSet.getString(1), presentedHash);
        if (familyIds.isEmpty()) {
            return Rotation.invalid(Status.INVALID_GRANT);
        }

        String familyId = familyIds.get(0);
        List<Family> families = jdbc.query("""
                        SELECT family_id, authorization_id, registered_client_id, resource, scopes, expires_at,
                               revoked_at
                        FROM mcp_oauth_refresh_token_family
                        WHERE family_id = ?
                        FOR UPDATE
                        """,
                FAMILY_MAPPER, familyId);
        List<TokenRow> tokens = jdbc.query("""
                        SELECT family_id, expires_at, consumed_at, revoked_at
                        FROM mcp_oauth_refresh_token
                        WHERE token_hash = ?
                        FOR UPDATE
                        """,
                TOKEN_MAPPER, presentedHash);
        if (families.isEmpty() || tokens.isEmpty()) {
            return Rotation.invalid(Status.INVALID_GRANT);
        }

        Family family = families.get(0);
        TokenRow token = tokens.get(0);
        if (!registeredClientId.equals(family.registeredClientId())
                || !resource.equals(family.resource())
                || !family.familyId().equals(token.familyId())) {
            return Rotation.invalid(Status.INVALID_GRANT);
        }
        if (family.revokedAt() != null || token.revokedAt() != null) {
            return Rotation.invalid(Status.INVALID_GRANT);
        }
        if (token.consumedAt() != null) {
            revokeFamily(family.familyId(), issuedAt);
            return Rotation.invalid(Status.REPLAYED);
        }
        if (!token.expiresAt().isAfter(issuedAt) || !family.expiresAt().isAfter(issuedAt)) {
            return Rotation.invalid(Status.INVALID_GRANT);
        }

        Set<String> authorizedScopes = parseScopes(family.scopes());
        Set<String> effectiveScopes = requestedScopes.isEmpty() ? authorizedScopes : new HashSet<>(requestedScopes);
        if (!authorizedScopes.containsAll(effectiveScopes)) {
            return Rotation.invalid(Status.INVALID_SCOPE);
        }

        String nextTokenValue = newTokenValue();
        Instant expiresAt = issuedAt.plus(ttl);
        int consumed = jdbc.update("""
                        UPDATE mcp_oauth_refresh_token
                        SET consumed_at = ?
                        WHERE token_hash = ? AND consumed_at IS NULL AND revoked_at IS NULL
                        """,
                Timestamp.from(issuedAt), presentedHash);
        if (consumed != 1) {
            revokeFamily(family.familyId(), issuedAt);
            return Rotation.invalid(Status.REPLAYED);
        }
        jdbc.update("""
                        INSERT INTO mcp_oauth_refresh_token
                            (token_hash, family_id, issued_at, expires_at)
                        VALUES (?, ?, ?, ?)
                        """,
                hash(nextTokenValue), family.familyId(), Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        jdbc.update("""
                        UPDATE mcp_oauth_refresh_token_family
                        SET expires_at = ?, last_used_at = ?
                        WHERE family_id = ? AND revoked_at IS NULL
                        """,
                Timestamp.from(expiresAt), Timestamp.from(issuedAt), family.familyId());

        return Rotation.success(family, effectiveScopes,
                new OAuth2RefreshToken(nextTokenValue, issuedAt, expiresAt));
    }

    private void revokeFamily(String familyId, Instant revokedAt) {
        Timestamp timestamp = Timestamp.from(revokedAt);
        jdbc.update("UPDATE mcp_oauth_refresh_token_family SET revoked_at = COALESCE(revoked_at, ?) WHERE family_id = ?",
                timestamp, familyId);
        jdbc.update("UPDATE mcp_oauth_refresh_token SET revoked_at = COALESCE(revoked_at, ?) WHERE family_id = ?",
                timestamp, familyId);
    }

    public static String newTokenValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String tokenValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tokenValue.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(Character.forDigit((item >> 4) & 0xF, 16));
                value.append(Character.forDigit(item & 0xF, 16));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static Set<String> parseScopes(String scopes) {
        Set<String> result = new HashSet<>();
        if (scopes == null || scopes.isBlank()) {
            return result;
        }
        for (String scope : scopes.split("\\s+")) {
            if (!scope.isBlank()) {
                result.add(scope);
            }
        }
        return result;
    }

    public enum Status {
        SUCCESS,
        INVALID_GRANT,
        INVALID_SCOPE,
        REPLAYED
    }

    public record Rotation(Status status, String authorizationId, Set<String> scopes,
                           OAuth2RefreshToken refreshToken) {
        private static Rotation success(Family family, Set<String> scopes, OAuth2RefreshToken refreshToken) {
            return new Rotation(Status.SUCCESS, family.authorizationId(), Set.copyOf(scopes), refreshToken);
        }

        private static Rotation invalid(Status status) {
            return new Rotation(status, null, Set.of(), null);
        }
    }

    private record Family(String familyId, String authorizationId, String registeredClientId,
                          String resource, String scopes, Instant expiresAt, Instant revokedAt) {
    }

    private record TokenRow(String familyId, Instant expiresAt, Instant consumedAt, Instant revokedAt) {
    }
}
