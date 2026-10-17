# Linear Lite 多 Agent MCP 接入实施与发布门槛 V1

记录日期：2026-10-14<br>
基线：`c7c1a9ad989890a23209d017e75a6350f274a902`

## 实施范围

| 项目 | 实施内容 | 发布状态 |
|---|---|---|
| 远程接入 | OAuth Agent 使用 `https://linearlite.eobua.cn/mcp`；VS Code 支持配置模板 | 服务端开关默认关闭 |
| 本机连接器 | Rust stdio 程序；OAuth、PKCE、loopback 回调、令牌刷新、MCP HTTP 转发 | GitHub Actions 生成 macOS、Windows、Linux 草稿发行包 |
| 凭据存储 | macOS Keychain、Windows Credential Manager、Linux Secret Service | 当前主机通过单元测试；其他平台待发行工作流构建 |
| VS Code Web | `/mcp`、OAuth 元数据、DCR 与 token endpoint 对 `https://vscode.dev` 提供 CORS | 随服务端开关启用 |
| 接入页 | `/settings/agent-connections`；远程地址、本机程序路径、按模式生成配置 | 前端开关默认关闭 |
| Codex | 插件包、工具注册、MCP 地址和授权记录保持原契约 | 团队账号前后回归未执行 |
| 二进制发行 | 标签流水线为四个平台组合生成草稿 Release | 尚无已发布连接器包 |

OAuth 授权码客户端保留原流程。DCR 请求 `refresh_token` 的新客户端才获得刷新能力。刷新令牌以 SHA-256 摘要存入独立表；数据库不保存原始刷新令牌。`mcp.oauth.agent-registration-enabled` 缺省为 `false`。

数据库表增量已追加到 `linear-lite-server/src/main/resources/schema.sql`；本任务没有连接或修改数据库。部署前需在目标数据库执行 schema 变更并核对结果。

## Codex 发布阻断条件

每次服务端发布前后，依照 [Codex 接入冻结基线 V1](Linear%20Lite%20MCP%20Codex%20接入冻结基线%20V1.md) 使用团队 Codex 账号完成以下核验：

1. MCP 连接状态正常，OAuth 状态有效。
2. 工具名称、顺序、输入 schema、scope 与冻结基线一致。
3. 代表性读取成功。
4. 指定回归项目中的低风险写入成功；不在团队业务数据执行破坏性操作。
5. 保存不含令牌、密码和用户数据的前后版本验收记录。

任一项失败、记录缺失或工具结果契约变化均为 NO-GO。停止发布或回滚服务端版本，不撤销成员授权。连接器和接入页可单独回滚。

冻结基线标出的 `list_projects`、`list_documents` 输出 schema 与数组结果不一致。工具名和结果形状属于既有 Codex 契约，本任务不改写。新 Agent 验收前需在独立测试账号验证客户端对这两个工具结果的处理；不通过则保持新接入开关关闭。

本次没有团队账号、测试 OAuth 客户端或服务器写入权限验收结果；不能开放新 Agent 配置。`mcp.oauth.agent-registration-enabled` 与 `VITE_MCP_AGENT_CONNECTIONS_ENABLED` 保持关闭，直到 Codex 回归通过且独立 OAuth 验收完成。

## 发行流程

1. 创建 `linear-lite-mcp-connector-v*` 标签后，GitHub Actions 为 Linux x64、macOS arm64/x64、Windows x64 构建程序并生成草稿 Release。
2. 草稿不面向用户；服务端发布前完成 Codex 基线回归和独立 OAuth 测试账号验收。
3. 服务端发布后重跑 Codex 回归；全部通过后，由发布负责人发布 GitHub Release，再启用服务端和接入页开关。
4. 失败时保留新接入开关关闭状态；若 Codex 回归失败，回滚服务端版本。草稿发行包和接入页可单独撤回。

访问者从 [Linear Lite GitHub Releases](https://github.com/hzwjs/linear-lite/releases) 下载与操作系统匹配的发行包。解压后，在 Agent 配置中填写连接器程序路径。配置不含用户名、密码或令牌。

## 本地验证记录

| 验证项 | 结果 |
|---|---|
| Rust 单元测试 | 3 项通过 |
| Rust 格式检查 | 通过 |
| 前端类型检查与生产构建 | 通过；构建输出有仓库分包警告 |
| 后端 OAuth 定向测试 | 12 项通过：DCR grant、redirect allowlist、CORS 开关、刷新令牌哈希 |
| Windows/Linux 编译 | 待发行工作流运行 |
| OAuth 浏览器授权与刷新 | 未用测试账号验收 |
| 服务器部署、数据库执行、Codex 前后回归 | 未执行 |
