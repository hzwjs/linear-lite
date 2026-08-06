package com.linearlite.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MCP 服务运行参数；协议版本固定在 MCP 适配层，不通过配置切换。 */
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private boolean enabled;
    private String allowedOrigins = "";
    private String serverName = "linear-lite";
    private String serverVersion = "0.1.0";
    private long toolListTtlMs = 300_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public long getToolListTtlMs() {
        return toolListTtlMs;
    }

    public void setToolListTtlMs(long toolListTtlMs) {
        this.toolListTtlMs = toolListTtlMs;
    }
}
