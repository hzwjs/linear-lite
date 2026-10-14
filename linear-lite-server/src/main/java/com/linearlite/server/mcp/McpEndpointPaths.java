package com.linearlite.server.mcp;

/** Endpoint paths shared by MCP routing and OAuth discovery. */
public final class McpEndpointPaths {

    public static final String MCP_ENDPOINT = "/mcp";
    public static final String PROTECTED_RESOURCE_METADATA =
            "/.well-known/oauth-protected-resource" + MCP_ENDPOINT;

    private McpEndpointPaths() {
    }
}
