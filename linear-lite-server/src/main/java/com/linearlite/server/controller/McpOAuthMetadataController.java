package com.linearlite.server.controller;

import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.mcp.McpEndpointPaths;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@ConditionalOnProperty(prefix = "mcp.oauth", name = "enabled", havingValue = "true")
public class McpOAuthMetadataController {

    private final McpOAuthProperties properties;

    public McpOAuthMetadataController(McpOAuthProperties properties) {
        this.properties = properties;
    }

    @GetMapping(path = {McpEndpointPaths.PROTECTED_RESOURCE_METADATA,
            "/.well-known/oauth-protected-resource"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        Map<String, Object> metadata = Map.of(
                "resource", properties.getResource(),
                "authorization_servers", List.of(properties.getIssuer()),
                "scopes_supported", List.of("linear-lite:read", "linear-lite:write"),
                "bearer_methods_supported", List.of("header"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(metadata);
    }
}
