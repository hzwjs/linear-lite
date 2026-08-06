package com.linearlite.server.mcp;

/** MCP 输入参数不符合工具 schema 时使用，映射为 JSON-RPC Invalid Params。 */
public class McpInvalidParamsException extends RuntimeException {

    public McpInvalidParamsException(String message) {
        super(message);
    }
}
