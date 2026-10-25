package com.linearlite.server.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;

class McpOAuthRefreshTokenStoreTest {

    @Test
    void storesOnlyRefreshTokenHash() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        McpOAuthRefreshTokenStore store = new McpOAuthRefreshTokenStore(jdbc);
        Instant issuedAt = Instant.parse("2026-10-14T00:00:00Z");

        var token = store.issue(
                "authorization-1",
                "client-1",
                "https://linearlite.eobua.cn/mcp",
                Set.of("linear-lite:read", "linear-lite:write"),
                issuedAt,
                Duration.ofDays(30));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sql.capture(), parameters.capture());
        int tokenInsert = IntStream.range(0, sql.getAllValues().size())
                .filter(index -> sql.getAllValues().get(index).toLowerCase()
                        .matches("(?s).*insert into mcp_oauth_refresh_token\\s*\\(.*"))
                .findFirst()
                .orElseThrow();

        String persistedValue = parameters.getAllValues().get(tokenInsert)[0].toString();
        assertEquals(64, persistedValue.length());
        assertTrue(persistedValue.matches("[0-9a-f]{64}"));
        assertNotEquals(token.getTokenValue(), persistedValue);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void revokesTheRefreshTokenFamilyForItsRegisteredClient() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        McpOAuthRefreshTokenStore store = new McpOAuthRefreshTokenStore(jdbc);
        Instant revokedAt = Instant.parse("2026-10-18T00:00:00Z");
        doReturn(java.util.List.of(new McpOAuthRefreshTokenStore.Family(
                "family-1", "authorization-1", "client-1", "resource", "linear-lite:read",
                revokedAt.plusSeconds(3600), null)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        var result = store.revoke("refresh-secret", "client-1", revokedAt);

        assertEquals("authorization-1", result.authorizationId());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("mcp_oauth_refresh_token_family")));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("mcp_oauth_refresh_token ")));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void doesNotRevokeAnotherClientsRefreshToken() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        McpOAuthRefreshTokenStore store = new McpOAuthRefreshTokenStore(jdbc);
        Instant revokedAt = Instant.parse("2026-10-18T00:00:00Z");
        doReturn(java.util.List.of(new McpOAuthRefreshTokenStore.Family(
                "family-1", "authorization-1", "owner-client", "resource", "linear-lite:read",
                revokedAt.plusSeconds(3600), null)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        var result = store.revoke("refresh-secret", "other-client", revokedAt);

        assertNull(result);
        verify(jdbc, times(0)).update(anyString(), any(Object[].class));
    }
}
