# LINEAR-LITE-78：多 Agent MCP 接入方案 V2

日期：2026-10-14<br>
状态：方案冻结；实现受第 4 节契约阻断项约束<br>
基线：[`Linear Lite MCP Codex 接入冻结基线 V1`](验收/Linear%20Lite%20MCP%20Codex%20接入冻结基线%20V1.md)

## 1. 结论

Linear Lite 保留一个远程 MCP 服务和唯一工具入口 `https://linearlite.eobua.cn/mcp`，为 Agent 提供两种接入方式：

1. 支持远程 Streamable HTTP 与 OAuth 的 Agent 直连 `/mcp`。
2. 只支持本机 stdio 的 Agent 运行同一套 Linear Lite 本机连接器；连接器通过 HTTPS 调用同一个 `/mcp`。

Codex 插件包、现有工具名与参数、OAuth scopes、现有客户端记录、授权记录和团队成员配置均列为冻结项。新能力只对新注册且声明相应 grant 的客户端生效。

VS Code 官方文档列出 stdio、Streamable HTTP 与 OAuth 支持，也要求 DCR 注册包含 `http://127.0.0.1:33418` 和 `https://vscode.dev/redirect` 回调。该文档只证明客户端能力，不代表当前 Linear Lite OAuth 流程已通过 VS Code 验收：[VS Code MCP 支持](https://code.visualstudio.com/api/extension-guides/ai/mcp)。

## 2. 变更前事实

基线记录见冻结基线 V1。2026-10-14 对线上只读元数据的核对结果：

| 检查项 | 结果 |
|---|---|
| 受保护资源 | `https://linearlite.eobua.cn/mcp` |
| Authorization Server | `https://linearlite.eobua.cn` |
| 资源元数据 | `/.well-known/oauth-protected-resource/mcp` 可读取；scope 为 `linear-lite:read`、`linear-lite:write` |
| 授权服务器元数据 | `/.well-known/oauth-authorization-server` 可读取；含授权码、DCR、公开客户端 `none`、S256 PKCE |
| 当前 grant 元数据 | 只有 `authorization_code`，没有 `refresh_token` |
| 目标回调 | HTTP loopback 已由 allowlist 支持；`https://vscode.dev/redirect` 未支持；同一注册请求不能混用 HTTP 与 HTTPS |
| 实际授权流程 | 未在生产新建客户端、未登录测试账号、未兑换或刷新 token |

线上元数据响应只作为只读现状证据。完整 PKCE、回调、授权码兑换、刷新和目标 Agent 调用须在独立测试客户端与测试账号上验收。

当前服务实现采用 MCP `2025-06-18`，以 `initialize`、`notifications/initialized` 和 `POST /mcp` 处理 Streamable HTTP。现有 `docs/LINEAR-LITE-78-mcp-service-plan.md` 中的 `2026-07-28` 与 `server/discover` 不代表当前运行契约；后续方案、配置和验收用当前源码与测试作准。

## 3. OAuth 实施范围

### 3.1 新客户端注册与令牌

- 保留现有授权码 + PKCE 路径；不改变既有授权码兑换语义。
- DCR 按请求的 grant 类型保存和返回能力。未请求 `refresh_token` 的新客户端继续只获授权码；请求刷新能力的新客户端需同时声明 `authorization_code` 与 `refresh_token`。
- Authorization Server 元数据只有在刷新实现与测试通过后才宣告 `refresh_token`。已登记客户端与已有授权记录不回填、不改写。
- 保留公开客户端认证方式 `none`、S256 PKCE、当前 issuer、resource 和 scopes。
- Spring Authorization Server 1.2.7 的默认 public-client converter/provider 不足以处理公开客户端刷新。新增受 RegisteredClient grant 限制的 refresh converter/provider；JWT access token 生成配置保留。
- 新刷新 token 采用轮换。新增令牌族记录，绑定 client、authorization、resource 和 scope；使用事务锁消费旧 token、签发新 token、更新授权。刷新 token 重放按 [RFC 9700 §4.14.2](https://datatracker.ietf.org/doc/html/rfc9700#section-4.14.2) 处置，撤销范围限于所属令牌族，不触碰 Codex 授权。令牌值只存哈希，不写日志。
- 服务端令牌族表的 DDL 进入 `linear-lite-server/src/main/resources/schema.sql`。生产 DDL 由 DBX 执行并核对；本方案阶段不触碰生产数据库。

### 3.2 回调与资源约束

- OAuth authorize 和 token 请求继续校验 `resource=https://linearlite.eobua.cn/mcp`。
- 回调采用逐 URI allowlist：保留现有 ChatGPT 回调，增加 VS Code 文档列出的 `http://127.0.0.1:33418` 与 `https://vscode.dev/redirect`。DCR 可登记不同 scheme 的回调，但不得放宽到任意 HTTPS URI。
- 本机连接器使用绑定 `127.0.0.1` 的一次性随机端口回调；校验 state 与 S256 verifier 后关闭监听器。
- 既有 loopback host/path 校验保留；端口动态匹配只用于本机回调。

### 3.3 OAuth 验收

使用独立测试客户端与测试账号验证：

1. 资源元数据指向唯一 `/mcp`，授权服务器元数据包含实际支持的 grant、S256 与 scopes。
2. DCR 分别覆盖未申请刷新和申请刷新两类新客户端，返回 grant 与注册请求一致。
3. 授权码流程校验 state、PKCE verifier、redirect URI、resource 与 scopes。
4. VS Code HTTP loopback、VS Code HTTPS redirect、本机连接器 loopback 回调都能完成授权。
5. 刷新 token 轮换成功；并发刷新只有一个有效消费结果；旧 token 重放只撤销当前客户端的所属授权。
6. 既有 Codex 客户端、授权记录与工具调用保持基线行为。

不得使用团队账号创建 OAuth 测试客户端，不得撤销或覆盖团队授权。

## 4. 未决阻断：工具结果 schema

当前 `list_projects` 和 `list_documents` 返回 JSON 数组；`McpDispatcher` 将原始值写入 `structuredContent`；工具目录却为工具输出统一声明 `{ "type": "object" }`。MCP 2025-06-18 要求 structured content 是 JSON object，且服务端结果须符合已提供的 output schema；客户端应按 schema 校验：[MCP Tools 规范](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)。

这与“冻结现有工具契约”形成冲突。Pi 适配器将数组转为 `{items: [...]}`，会改写结果契约，不能复用为本机连接器的协议转发逻辑。方案先保持工具输出和名称不变；在目标 VS Code 版本完成隔离验收前，不宣称远程直连已兼容，不发布新接入方式。若 VS Code 拒绝当前结果，暂停实现并取得工具结果契约变更决定；不得由连接器静默改写。

架构审查记录：Jev review `9369b45f-3cd0-41e2-ba94-de51ba9eb302`，状态 `blocked`。审查还要求验证刷新 token 族的事务与重放语义后才能进入服务端实现。

## 5. 本机连接器

发布一个 Rust stdio 可执行程序，按 Windows、macOS、Linux 与目标 CPU 架构提供发行包。使用[官方 MCP Rust SDK](https://github.com/modelcontextprotocol/rust-sdk)处理 stdio 消息和 Streamable HTTP 客户端传输；依赖版本须先通过 `2025-06-18` 协议协商验证。连接器负责 JSON-RPC 转发、Authorization 与 HTTP 错误映射。

连接器职责限定为：

- 首次调用时读取资源与授权服务器元数据，执行 DCR、浏览器授权、S256 PKCE 和 loopback 回调。
- 将 access token、refresh token、client ID、resource 与到期时间作为单一账户凭据存入系统凭据库；以 resource 和账户标识作为 keyring account key。日志、stdout、配置文件不出现 token。
- 使用 Rust [`keyring`](https://docs.rs/keyring/latest/keyring/) 将凭据写入平台凭据库：macOS Keychain、Windows Credential Manager、Linux Secret Service。Linux 凭据服务缺失或锁定时返回可见错误；禁止明文文件回退。
- 刷新前使用跨进程锁，并在锁内重读凭据；成功轮换后原子写回。授权失败时清除失效凭据并提示重新连接。
- stdout 只承载 MCP stdio 消息，诊断信息写 stderr。传输超时不自动重试写工具调用。
- 代理 `tools/list` 与 `tools/call` 时保留工具名、参数、业务结果和错误。若服务端或目标客户端要求结果结构变化，按第 4 节阻断规则处理。

Agent 配置只提供本机程序路径与 MCP server 名称；不写账号、密码、client secret、access token、refresh token 或环境变量秘密。程序从首次交互触发浏览器授权，避免在无人值守启动时占用 stdio。

## 6. 统一接入页

在 Linear Lite 增加一页“连接 AI Agent”，提供两张接入卡片：

| 接入方式 | 展示内容 | 配置生成 |
|---|---|---|
| 远程 MCP | 服务地址、支持 OAuth 的 Agent 名单与授权流程 | 只输出 `https://linearlite.eobua.cn/mcp` 与 Agent 所需传输类型 |
| 本机 stdio | Windows/macOS/Linux 安装包、版本与启动方式 | 只输出本机 connector 路径与 Agent 所需 server 名称 |

页面按 Agent 选择配置格式，并提供复制、下载与重置操作。页面不收集账号密码或令牌。只有第 7 节发布门禁通过后，才展示可下载连接器和新 Agent 配置入口。

## 7. 发布阻断与回滚

### 7.1 阻断条件

每次服务端发布都以同一个 Codex 团队账号，在候选版本部署前与部署后完成基线回归。发布记录缺少任一项或任一项失败，均为 **NO-GO**：

1. Codex 连接状态可用。
2. `tools/list` 返回冻结基线中的工具名称、顺序与 scope 结果。
3. 代表性读取：`get_profile`、`list_projects`、读取指定验收任务。
4. 代表性写入：在指定 MCP 回归项目的专用验收任务上追加带版本号的评论；不删除对象、不覆盖业务字段。
5. 保存服务版本、时间、连接状态、工具列表摘要、读写是否成功和操作者；不保存 token、评论正文、完整响应或其他业务数据。

回归项目与验收任务须由项目负责人指定并纳入基线。未指定前，不对团队数据执行写入验证。

本仓库没有 `.github/workflows` 发布工作流，当前没有可接入的 CI 发布检查点。本方案把签字清单设为人工发布阻断项；实际部署入口接入此清单前，不开放新接入页面、连接器下载或新增 Agent 宣传。后续部署自动化必须要求回归证据才能继续发布。

### 7.2 回滚

- Codex 前置回归失败：停止本次发布。
- Codex 后置回归失败：回滚服务端应用版本，并复测冻结基线。
- 服务端回滚不得回灌旧数据库快照、撤销团队授权或更换现有签名配置；OAuth 新增数据保留，由兼容的旧版本读取。
- 连接器与接入页单独发布、单独回滚。服务端回滚后隐藏新接入入口并停止新连接器下载；团队 Codex 插件和已有授权保持原状。

## 8. 交付顺序与验收

1. 保存 Codex 接入冻结基线与发布回归表。
2. 在独立环境完成工具输出兼容核验；存在 schema 拒绝时暂停，并保持工具契约冻结。
3. 为新注册客户端完成 OAuth 刷新、回调 allowlist 与令牌族实现；定向测试覆盖 client grant 隔离、PKCE、刷新轮换、重放和现有客户端不变。
4. 先建立 Codex 发布阻断流程并完成一次基线演练，再部署 OAuth 服务端变更。
5. 使用独立测试客户端和测试账号完成 OAuth、VS Code 远程调用、本机连接器授权、刷新、读工具和隔离测试写工具。
6. 发布 Windows、macOS、Linux 连接器与 Agent 配置页；Codex 回归门禁持续生效。

开放新接入方式的完成标准：Codex 团队成员不重装、不重新授权、不改配置；测试账号可通过远程 OAuth 或本机 stdio 连接器授权并调用 Linear Lite 工具；每次服务端发布都有通过的 Codex 前后回归记录；第 4 节阻断已通过目标 Agent 验收或另行获得契约变更决定。
