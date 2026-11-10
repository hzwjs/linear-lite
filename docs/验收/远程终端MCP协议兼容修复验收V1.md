# 远程终端 MCP 协议兼容修复验收 V1

日期：2026-10-28。

## 结论与范围

修复标准 MCP 初始化被拒绝的问题。Codex CLI 0.164.0 使用本机注册的命令与运行制品，完成 MCP 握手和工具发现，返回八个工具，`toolsError=null`。

修改范围为 `remote-terminal-mcp/src/main.ts`、`remote-terminal-mcp/test/mcp.test.ts`，并构建 `remote-terminal-mcp/dist/`。依赖、工具参数、SSH/SFTP 逻辑、服务器目标及凭据配置不变。无提交或推送。

## 根因与处理

SDK 2.4.0 的 stdio 入口按开场消息选择协议：没有现代 `_meta` 的 `initialize` 属于标准握手；携带现代 `_meta` 的 `server/discover` 属于现代发现流程。原入口设置 `legacy: 'reject'`，拒绝前一种流程。把标准握手中的版本写成 `2026-07-28` 不会改变消息分类。[SDK stdio 实现](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/packages/server/src/server/serveStdio.ts)

标准握手需要 `initialize`、`notifications/initialized`，随后才能开展工具交互。旧测试把拒绝标准握手作为预期，并使用现代发现流程验证工具，因此遗漏 Codex 的请求路径。[MCP 2025-06-18 生命周期](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)

入口改用 SDK 原生 `legacy: 'serve'`。工厂创建 `McpServer` 实例，八个工具使用同一注册入口。业务资源清理绑定物理 stdio 关闭；现代发现探测实例被 SDK 丢弃时，不关闭共享的 `TerminalService`。没有添加协议转换层或业务数据回退。

## 验证证据

### Codex 客户端

启动本机 `codex app-server --listen stdio://`，发送 app-server 的 `initialize` 与 `initialized`，随后请求：

```json
{"method":"mcpServerStatus/list","params":{"serverName":"remote-terminal","detail":"toolsAndAuthOnly"}}
```

app-server 使用现有 MCP 注册配置启动服务，承担其 MCP 握手与 `tools/list`。未创建对话、调用模型或执行远端命令。

| 检查 | 修复前 | 修复后 |
| --- | --- | --- |
| 工具数量 | 0 | 8 |
| 发现错误 | `-32022 Unsupported protocol version: 2025-06-18` | `null` |
| 服务信息 | 无 | `remote-terminal-mcp 0.1.0` |
| 工具输入与输出结构 | 无 | 八个工具具备对应结构 |

工具：`list_targets`、`open_session`、`close_session`、`exec`、`read_output`、`write_stdin`、`upload`、`download`。

### 构建产物的协议矩阵

使用 Node v22.19.0 启动 `dist/src/main.js`，配置为空目标列表。各路径完成工具发现、`list_targets` 调用与 stdin EOF 退出，工具数量为 8，进程退出码为 0。

| 开场请求 | 协商结果 |
| --- | --- |
| 标准 `initialize`，版本 `2025-06-18` | `2025-06-18` |
| 标准 `initialize`，版本 `2025-11-25` | `2025-11-25` |
| 标准 `initialize`，版本 `2026-07-28` | `2025-11-25` |
| `server/discover`，现代 `_meta` 版本 `2026-07-28` | `2026-07-28` |

第三行是标准握手的版本协商，不代表使用现代协议。

### 回归与技术核对

- `npm test`：29/29 通过，无失败或跳过。覆盖标准握手、现代发现、探测转标准握手、固定现代连接后的协议拒绝、参数校验、错误脱敏、EOF 与重复关闭，以及现有 SSH/SFTP 集成测试。
- `npm run typecheck`：退出码 0。
- `npm run build`：退出码 0。
- architect 技术核对：协议选择、实例工厂、资源关闭边界无阻断问题；结论绑定下列源码、测试与制品指纹。

| 对象 | SHA-256 |
| --- | --- |
| `src/main.ts` | `67c286a8f7a814167cb7797133e78104cb8adfd03aca3c9828d6c2b73a773abc` |
| `test/mcp.test.ts` | `3c737f62ceb26f994b82950a0c48f84f9efd2889f41180b3587d1754b40378d0` |
| `dist/src/main.js` | `2091be4d4be5902393eeb308b914f0bec1af93313b773d389f7e8644d2369dd6` |

### 配置保留

检查前后 SHA-256 相同：

| 对象 | SHA-256 |
| --- | --- |
| `~/.config/remote-terminal/targets.json` | `be25462a2dd6bf34f6cd4795a18a609ae92a776ca21e9acdc5c353bb5257986b` |
| `~/.codex/config.toml` | `711a04c505906e4e8e7fd3001eb64a940098a737d26d5a732230a2678efb8eab` |

目标配置权限为 `600`。验收材料不包含凭据内容。

## 当前会话与未验证项

新启动的 Codex app-server 能发现八个工具；当前对话的工具目录没有 `remote-terminal`。需要客户端重载 MCP；客户端没有重载入口时，需要重启 Codex。应用未在本轮重启，当前对话工具热加载不在验证结论中。

E2E 未执行：用户未显式调用。未执行真实服务器命令、文件上传下载或 EasyGo H5 部署。本轮验收范围为协议集成、回归测试与运行制品。

## 实施事项清单

- 启用标准初始化与版本协商。
- 保留现代协议发现。
- 绑定连接关闭与资源清理。
- 补充协议回归测试。
- 构建制品并验证 Codex 发现。
