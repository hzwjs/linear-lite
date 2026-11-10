package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskExecutionClaimCredentialServiceTest {
    private TaskExecutionClaimCredentialService service(String key) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(key);
        return new TaskExecutionClaimCredentialService(properties, new ObjectMapper());
    }

    @Test void sameClaimCanReplayAndOnlyMatchingHashAuthorizes() {
        var credentials = service("unit-test-claim-key");
        String token = credentials.issue("batch", 1L, 2L, "host", "agent", "op");
        assertEquals(token, credentials.issue("batch", 1L, 2L, "host", "agent", "op"));
        assertTrue(credentials.matches(token, credentials.hash(token)));
        assertFalse(credentials.matches("other", credentials.hash(token)));
        assertFalse(credentials.matches(null, credentials.hash(token)));
        assertEquals(64, credentials.hash(token).length());
    }

    @Test void operationInstanceBindingBatchTaskAndKeySeparateClaims() {
        var credentials = service("unit-test-claim-key");
        String token = credentials.issue("batch", 1L, 2L, "host", "agent", "op");
        assertNotEquals(token, credentials.issue("batch", 1L, 2L, "host", "agent", "other"));
        assertNotEquals(token, credentials.issue("batch", 1L, 2L, "host", "other", "op"));
        assertNotEquals(token, credentials.issue("batch", 1L, 2L, "other", "agent", "op"));
        assertNotEquals(token, credentials.issue("batch", 3L, 2L, "host", "agent", "op"));
        assertNotEquals(token, credentials.issue("batch", 1L, 3L, "host", "agent", "op"));
        assertNotEquals(token, credentials.issue("other", 1L, 2L, "host", "agent", "op"));
        assertNotEquals(token, service("another-unit-key").issue("batch", 1L, 2L, "host", "agent", "op"));
    }

    @Test void parameterBoundariesAreUnambiguous() {
        var credentials = service("unit-test-claim-key");
        assertNotEquals(credentials.issue("a|b", 1L, 2L, "c", "d", "e"),
                credentials.issue("a", 1L, 2L, "b|c", "d", "e"));
    }
}
