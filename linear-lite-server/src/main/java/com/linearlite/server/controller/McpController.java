package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.mcp.McpDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/** MCP 2026-07-28 唯一 HTTP 入口；旧 SSE、GET 和 DELETE 传输不注册。 */
@RestController
@RequestMapping("/mcp")
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
public class McpController {

    private final McpDispatcher dispatcher;

    public McpController(McpDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> post(
            HttpServletRequest request,
            @RequestBody(required = false) String body,
            @RequestHeader(name = McpDispatcher.PROTOCOL_VERSION_HEADER, required = false)
            String protocolVersion,
            @RequestHeader(name = McpDispatcher.METHOD_HEADER, required = false) String method,
            @RequestHeader(name = McpDispatcher.NAME_HEADER, required = false) String name) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, protocolVersion, method, name, request.getHeader("Origin"), userId);
        if (response.body() == null) {
            return ResponseEntity.status(response.status()).build();
        }
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    @GetMapping
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(405).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        return ResponseEntity.status(405).build();
    }
}
