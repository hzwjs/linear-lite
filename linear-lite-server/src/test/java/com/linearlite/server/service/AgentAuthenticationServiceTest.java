package com.linearlite.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AgentAuthenticationServiceTest {
    @Test
    void hashesAgentTokenAsStableSha256WithoutPersistingPlaintext() {
        String first = AgentAuthenticationService.hash("pi_test_token");
        String second = AgentAuthenticationService.hash("pi_test_token");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals(first, "pi_test_token");
    }
}
