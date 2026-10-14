package com.linearlite.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.linearlite.server.security.LinearLiteUserDetails;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class McpOAuthAuthorizationObjectMapperTest {

    @Test
    void serializesAndDeserializesLinearLiteUserDetails() throws Exception {
        ObjectMapper mapper = McpOAuthServerConfiguration.mcpOAuthAuthorizationObjectMapper();
        LinearLiteUserDetails user = new LinearLiteUserDetails(42L, "local-test", true);
        Map<String, Object> tokenClaims = new LinkedHashMap<>();
        tokenClaims.put("aud", new ArrayList<>(List.of("https://linearlite.example/mcp")));
        tokenClaims.put("scope", new ArrayList<>(List.of("linear-lite:read", "linear-lite:write")));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("principal", user);
        attributes.put("claims", tokenClaims);

        Map<String, Object> restored = mapper.readValue(
                mapper.writeValueAsString(Collections.unmodifiableMap(attributes)), new TypeReference<>() { });

        LinearLiteUserDetails restoredUser = assertInstanceOf(
                LinearLiteUserDetails.class, restored.get("principal"));
        assertEquals(user.getUserId(), restoredUser.getUserId());
        assertEquals(user.getUsername(), restoredUser.getUsername());
        assertEquals(user.isEnabled(), restoredUser.isEnabled());
        Map<?, ?> restoredClaims = assertInstanceOf(Map.class, restored.get("claims"));
        assertEquals(new ArrayList<>(List.of("linear-lite:read", "linear-lite:write")),
                restoredClaims.get("scope"));
    }
}
