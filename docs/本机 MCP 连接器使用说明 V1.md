# Linear Lite 本机 MCP 连接器使用说明 V1

## 安装

Codex 回归验收和独立 OAuth 测试通过后，发行包会出现在 Releases 页面。

1. 打开 [Linear Lite Releases](https://github.com/hzwjs/linear-lite/releases)。
2. 下载名称含操作系统和 CPU 架构的发行包，解压到本机应用目录。
3. 在 Agent 的 MCP 配置中填写 `linear-lite-mcp-connector` 可执行文件完整路径。

macOS 提供 Apple 芯片和 Intel 版本；Windows 与 Linux 提供 x64 版本。Linux 首次运行需桌面 Secret Service 和 D-Bus 会话。

## VS Code 配置

`mcp.json` 可使用本机连接器：

```json
{
  "servers": {
    "linear-lite": {
      "type": "stdio",
      "command": "/path/to/linear-lite-mcp-connector",
      "args": []
    }
  }
}
```

Windows 的 `command` 值使用 `.exe` 文件完整路径。Agent 启动连接器后，连接器会打开浏览器授权；令牌由操作系统凭据库保存。

## 远程配置

支持 Streamable HTTP 和 OAuth 的 Agent 可连接 `https://linearlite.eobua.cn/mcp`，按 Agent 文档登记 HTTP MCP server。OAuth 由 Agent 处理。

## 其他 stdio Agent

stdio 配置格式由 Agent 决定。把连接器的可执行路径和空参数列表放入 Agent 的 MCP stdio 配置项：

```json
{
  "command": "/path/to/linear-lite-mcp-connector",
  "args": []
}
```

配置中不填写用户名、密码或令牌。授权令牌存入操作系统凭据库。
