# Linear Lite MCP Codex 接入冻结基线 V1

记录日期：2026-10-14<br>
代码基线：`c7c1a9ad989890a23209d017e75a6350f274a902`<br>
用途：冻结本次接入改动前可观察到的 Codex MCP 配置与服务契约，作为服务端发布前后回归依据。

## 1. 观测范围

本记录依据当前工作区源码、当前机器 Codex CLI/插件状态和线上 OAuth 元数据。它不读取 token 或密码，也不代表读取到所有团队成员设备上的本机配置或授权记录。团队成员各自配置与授权不进入本次变更范围。

当前工作区没有已跟踪 OAuth 源码差异；HEAD 包含 MCP OAuth 提交 `0628c6d feat(mcp): 增加 OAuth 授权与用户资料查询`。未提交的 `.env`、PID 文件不在本任务范围内，内容未读取。

## 2. Codex 接入契约

当前机器安装状态：

| 项目 | 基线 |
|---|---|
| Codex marketplace | `team-plugins` |
| 插件 ID | `linear-lite@team-plugins` |
| 插件版本 | `0.3.0`，enabled |
| MCP URL | `https://linearlite.eobua.cn/mcp` |
| Transport | HTTP / Streamable HTTP |
| OAuth client mode | `cimd` |
| scopes | `linear-lite:read linear-lite:write` |

当前安装包的 `.mcp.json` 配置：

```json
{
  "mcpServers": {
    "linear-lite": {
      "type": "http",
      "url": "https://linearlite.eobua.cn/mcp",
      "extensions": {
        "com.openai": {
          "auth": {
            "type": "oauth",
            "client": { "mode": "cimd" },
            "baseScopes": ["linear-lite:read", "linear-lite:write"]
          }
        }
      }
    }
  }
}
```

配置来自本机已安装的团队插件包。此记录不把个人临时安装路径当成团队契约，也不记录授权 token、账户名或密码。

### 2.1 可复现连接流程

1. 在 Codex 中启用团队 marketplace 的 `linear-lite` 插件；确认插件版本与 MCP URL 和本基线一致。
2. 在 Codex MCP 状态页确认 `linear-lite` 已连接且 OAuth 状态有效。
3. 若客户端显示授权请求，在 Linear Lite 登录页完成登录与授权，再回到 Codex。
4. 调用 `get_profile` 验证当前用户，再调用 `list_projects` 验证读取权限。
5. 发布回归时，只在指定 MCP 回归项目的验收任务上执行代表性写入；不得用普通团队任务作为写入样本。

上述流程是复现步骤，不是本记录对团队成员当前在线连接的证明。每次发布前后需保留当次状态证据。

## 3. 工具名、scope 与顺序

`tools/list` 的冻结名称顺序：

1. `get_profile` — 不要求 MCP scope。
2. `list_projects` — `linear-lite:read`
3. `create_project` — `linear-lite:write`
4. `create_task` — `linear-lite:write`
5. `update_task` — `linear-lite:write`
6. `add_task_comment` — `linear-lite:write`
7. `get_task` — `linear-lite:read`
8. `list_documents` — `linear-lite:read`
9. `get_document` — `linear-lite:read`
10. `get_document_blocks` — `linear-lite:read`
11. `create_document` — `linear-lite:write`
12. `update_document` — `linear-lite:write`
13. `patch_document` — `linear-lite:write`

回归比较工具名称、顺序、输入 schema 与 scope 行为。工具参数、成功结果、业务错误与文档乐观锁语义也属于冻结范围。

## 4. 当前服务端 OAuth 与协议

### 4.1 OAuth 配置与线上只读元数据

2026-10-14 读取：

- 受保护资源元数据：`https://linearlite.eobua.cn/.well-known/oauth-protected-resource/mcp`
- 授权服务器元数据：`https://linearlite.eobua.cn/.well-known/oauth-authorization-server`
- resource：`https://linearlite.eobua.cn/mcp`
- issuer：`https://linearlite.eobua.cn`
- 授权端点：`/oauth2/authorize`
- token 端点：`/oauth2/token`
- DCR 端点：`/oauth2/register`
- 公共客户端认证方式：`none`
- PKCE：`S256`
- grant：`authorization_code`
- scopes：`linear-lite:read`、`linear-lite:write`
- Bearer 传输：HTTP Authorization header

元数据、resource 和 S256 与目标 Agent 的首步发现条件一致。线上元数据没有宣告 `refresh_token`。本次没有创建生产 DCR 客户端、发起新授权、兑换 token 或测试刷新流程。

### 4.2 回调 allowlist

当前回调源码允许 ChatGPT 固定 HTTPS 路径和本机 loopback HTTP；DCR 同一组回调还要求 scheme 相同。VS Code 的 `https://vscode.dev/redirect` 尚未允许；`http://127.0.0.1:33418` 属于已允许的 loopback HTTP 回调：

- `https://vscode.dev/redirect`

### 4.3 MCP 传输与结果

- Endpoint：`POST /mcp`；当前业务读取工具目录与调用工具共用此入口。
- 服务端 `McpDispatcher.PROTOCOL_VERSION`：`2025-06-18`。
- `list_projects` 与 `list_documents` 返回数组。
- 两个工具的原始数组进入 `structuredContent`，但工具输出 schema 是 `type: object`。此项列为新 Agent 发布阻断，不能通过本机连接器转换结果来隐藏。

旧技术方案文档写有 `2026-07-28` 和 `server/discover`，与当前源码不一致；本冻结基线以 dispatcher 和工具注册源码为准。

## 5. 发布前后回归记录模板

每次服务端发布新增一份记录，不覆盖本文件：

| 字段 | 记录值 |
|---|---|
| 候选与发布版本 |  |
| Codex 客户端/插件版本 |  |
| 回归账号标识（不含凭据） |  |
| 发布前连接状态 |  |
| 发布前工具名与顺序 |  |
| 发布前读取结果 |  |
| 发布前专用验收任务写入结果 |  |
| 发布后连接状态 |  |
| 发布后工具名与顺序 |  |
| 发布后读取结果 |  |
| 发布后专用验收任务写入结果 |  |
| 判定 | GO / NO-GO |
| 操作者与时间 |  |

连接失败、工具名或顺序变化、读写失败、缺少记录均为 NO-GO。不得记录 token、密码或团队用户数据。NO-GO 时停止发布或回滚服务端版本；不撤销 Codex 授权，不回灌 OAuth 数据库快照。
