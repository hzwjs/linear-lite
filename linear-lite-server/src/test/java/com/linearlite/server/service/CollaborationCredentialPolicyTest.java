package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollaborationCredentialPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void rejectsCredentialInsideNestedBusinessTextAndOperationId() throws Exception {
        var request = json.readTree("{\"checkpoint\":{\"finding\":\"found fixture-claim\",\"artifactRefs\":[\"docs/a\"]}}");
        var error = assertThrows(IllegalArgumentException.class,
                () -> CollaborationCredentialPolicy.requireSafe(request, "fixture-batch", "fixture-claim"));
        assertEquals("协作业务材料包含执行凭据", error.getMessage());
        assertFalse(error.getMessage().contains("fixture-claim"));
        assertThrows(IllegalArgumentException.class,
                () -> CollaborationCredentialPolicy.requireSafe("operation-fixture-batch", "fixture-batch"));
    }

    @Test void permitsOrdinaryFactsAndAbsentCredential() throws Exception {
        var request = json.readTree("{\"finding\":\"implemented parser\",\"artifactRefs\":[\"docs/a\"],\"version\":3}");
        assertDoesNotThrow(() -> CollaborationCredentialPolicy.requireSafe(request, "fixture-batch", "fixture-claim", null));
    }
}
