package com.linearlite.server.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.config.McpProperties;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.exception.UnprocessableEntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/** MCP 2026-07-28 JSON-RPC dispatcher；每个 HTTP 请求独立完成协议解析和工具执行。 */
@Component
public class McpDispatcher {

    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    public static final String METHOD_HEADER = "Mcp-Method";
    public static final String NAME_HEADER = "Mcp-Name";
    private static final String CLIENT_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    private static final Set<String> SUPPORTED_METHODS = Set.of("server/discover", "tools/list", "tools/call");
    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

    private final ObjectMapper objectMapper;
    private final McpProperties properties;
    private final McpToolRegistry toolRegistry;

    public McpDispatcher(ObjectMapper objectMapper, McpProperties properties, McpToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.toolRegistry = toolRegistry;
    }

    public DispatchResponse dispatch(
            String rawBody,
            String protocolVersionHeader,
            String methodHeader,
            String nameHeader,
            String origin,
            Long userId) {
        if (!isAllowedOrigin(origin)) {
            return new DispatchResponse(403, null);
        }
        if (userId == null) {
            return new DispatchResponse(401, null);
        }

        JsonNode request;
        try {
            request = objectMapper.readTree(rawBody == null ? "" : rawBody);
        } catch (JsonProcessingException e) {
            return protocolError(null, 400, -32600, "请求不是有效的 JSON", null);
        }
        if (request == null || !request.isObject()) {
            return protocolError(null, 400, -32600, "JSON-RPC 请求必须是对象", null);
        }

        JsonNode id = request.get("id");
        if (id == null || id.isNull() || (!id.isTextual() && !id.isIntegralNumber())) {
            return protocolError(null, 400, -32600, "JSON-RPC request id 必须是字符串或整数", null);
        }
        if (!"2.0".equals(request.path("jsonrpc").asText())) {
            return protocolError(id, 400, -32600, "jsonrpc 必须是 2.0", null);
        }

        String method = request.path("method").isTextual() ? request.path("method").textValue() : null;
        if (method == null || method.isBlank()) {
            return protocolError(id, 400, -32600, "method 必须是非空字符串", null);
        }
        if (!SUPPORTED_METHODS.contains(method)) {
            return protocolError(id, 404, -32601, "方法不存在: " + method, null);
        }

        if (protocolVersionHeader == null || protocolVersionHeader.isBlank()
                || methodHeader == null || methodHeader.isBlank()) {
            return protocolError(id, 400, -32020, "缺少 MCP-Protocol-Version 或 Mcp-Method 请求头", null);
        }
        if (!method.equals(methodHeader)) {
            return protocolError(id, 400, -32020, "Mcp-Method 与请求体 method 不一致", null);
        }
        if (!PROTOCOL_VERSION.equals(protocolVersionHeader)) {
            return unsupportedVersion(id);
        }

        JsonNode params = request.get("params");
        if (params == null || !params.isObject()) {
            return protocolError(id, 400, -32602, "params 必须是对象", null);
        }
        JsonNode meta = params.get("_meta");
        if (meta == null || !meta.isObject()
                || !meta.path(CLIENT_PROTOCOL_VERSION).isTextual()
                || !meta.path(CLIENT_CAPABILITIES).isObject()) {
            return protocolError(id, 400, -32602,
                    "params._meta 必须包含协议版本和 clientCapabilities", null);
        }
        if (!PROTOCOL_VERSION.equals(meta.path(CLIENT_PROTOCOL_VERSION).textValue())) {
            return protocolError(id, 400, -32020,
                    "请求体协议版本与 MCP-Protocol-Version 不一致", null);
        }

        if ("tools/call".equals(method)) {
            JsonNode toolName = params.get("name");
            if (toolName == null || !toolName.isTextual() || toolName.textValue().isBlank()) {
                return protocolError(id, 400, -32602, "tools/call 缺少 name", null);
            }
            if (nameHeader == null || nameHeader.isBlank() || !toolName.textValue().equals(nameHeader)) {
                return protocolError(id, 400, -32020, "Mcp-Name 与请求体 name 不一致", null);
            }
        }

        return switch (method) {
            case "server/discover" -> discover(id);
            case "tools/list" -> listTools(id);
            case "tools/call" -> callTool(id, params, userId);
            default -> protocolError(id, 404, -32601, "方法不存在: " + method, null);
        };
    }

    private DispatchResponse discover(JsonNode id) {
        ObjectNode result = completeResult();
        ArrayNode versions = result.putArray("supportedVersions");
        versions.add(PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        result.put("instructions", "使用 tools/list 获取工具目录；所有写操作都以当前认证用户身份执行。");
        addCacheHints(result, true);
        return success(id, result);
    }

    private DispatchResponse listTools(JsonNode id) {
        ObjectNode result = completeResult();
        result.set("tools", toolRegistry.definitions());
        addCacheHints(result, true);
        return success(id, result);
    }

    private DispatchResponse callTool(JsonNode id, JsonNode params, Long userId) {
        JsonNode arguments = params.get("arguments");
        if (arguments == null) {
            arguments = objectMapper.createObjectNode();
        }
        try {
            JsonNode value = toolRegistry.invoke(params.path("name").textValue(), arguments, userId);
            ObjectNode result = completeResult();
            result.set("structuredContent", value);
            ArrayNode content = result.putArray("content");
            content.addObject().put("type", "text").put("text", objectMapper.writeValueAsString(value));
            return success(id, result);
        } catch (McpInvalidParamsException e) {
            return protocolError(id, 400, -32602, e.getMessage(), null);
        } catch (Exception e) {
            log.warn("MCP tool execution failed, tool={}, userId={}, error={}",
                    params.path("name").asText(), userId, e.getMessage());
            return success(id, toolError(e));
        }
    }

    private ObjectNode toolError(Exception exception) {
        String errorType = errorType(exception);
        String message = exception.getMessage() == null ? "工具执行失败" : exception.getMessage();
        ObjectNode result = completeResult();
        result.put("isError", true);
        result.putArray("content").addObject().put("type", "text").put("text", message);
        ObjectNode structured = result.putObject("structuredContent");
        structured.put("errorType", errorType);
        structured.put("message", message);
        if (exception instanceof DocumentVersionConflictException conflict) {
            structured.put("currentVersion", conflict.getCurrentVersion());
        }
        return result;
    }

    private static String errorType(Exception exception) {
        if (exception instanceof McpToolRegistry.McpToolNotFoundException) {
            return "TOOL_NOT_FOUND";
        }
        if (exception instanceof ResourceNotFoundException) {
            return "RESOURCE_NOT_FOUND";
        }
        if (exception instanceof ForbiddenOperationException) {
            return "FORBIDDEN";
        }
        if (exception instanceof DocumentVersionConflictException) {
            return "VERSION_CONFLICT";
        }
        if (exception instanceof ConflictOperationException) {
            return "CONFLICT";
        }
        if (exception instanceof UnprocessableEntityException) {
            return "UNPROCESSABLE_ENTITY";
        }
        if (exception instanceof IllegalArgumentException) {
            return "BUSINESS_VALIDATION_FAILED";
        }
        return "INTERNAL_ERROR";
    }

    private ObjectNode completeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("resultType", "complete");
        ObjectNode meta = result.putObject("_meta");
        ObjectNode serverInfo = meta.putObject(SERVER_INFO);
        serverInfo.put("name", properties.getServerName());
        serverInfo.put("version", properties.getServerVersion());
        return result;
    }

    private void addCacheHints(ObjectNode result, boolean privateScope) {
        result.put("ttlMs", Math.max(0L, properties.getToolListTtlMs()));
        result.put("cacheScope", privateScope ? "private" : "public");
    }

    private DispatchResponse success(JsonNode id, ObjectNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return new DispatchResponse(200, response);
    }

    private DispatchResponse unsupportedVersion(JsonNode id) {
        ObjectNode data = objectMapper.createObjectNode();
        ArrayNode supported = data.putArray("supportedVersions");
        supported.add(PROTOCOL_VERSION);
        return protocolError(id, 400, -32022, "不支持的 MCP 协议版本", data);
    }

    private DispatchResponse protocolError(JsonNode id, int status, int code, String message, JsonNode data) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            response.set("id", id);
        } else {
            response.putNull("id");
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", data);
        }
        return new DispatchResponse(status, response);
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (properties.getAllowedOrigins() == null || properties.getAllowedOrigins().isBlank()) {
            return false;
        }
        return Arrays.stream(properties.getAllowedOrigins().split(","))
                .map(String::trim)
                .anyMatch(origin::equals);
    }

    public record DispatchResponse(int status, JsonNode body) {
    }
}
