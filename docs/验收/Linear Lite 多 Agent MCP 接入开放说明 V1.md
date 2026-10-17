# Linear Lite 多 Agent MCP 接入开放说明 V1

记录日期：2026-10-14

## 当前部署规则

本说明取代《Linear Lite 多 Agent MCP 接入实施与发布门槛 V1》《Linear Lite MCP Codex 接入冻结基线 V1》及《LINEAR-LITE-78 多 Agent MCP 接入方案 V2》中 Codex 回归阻断发布、强制写入验收和等待开关启用的要求。冻结基线中的地址、工具名、scope 与插件信息继续作为既有契约参考；历史版本保留作变更记录，不作为本次部署阻断条件。

- 远程 Agent 使用 `https://linearlite.eobua.cn/mcp`，通过 OAuth、PKCE 和动态客户端注册接入。
- 新注册客户端可申请 `refresh_token`；令牌刷新与轮换由服务端处理。
- VS Code Web 的固定 OAuth 回调和 CORS 规则随服务端提供。
- `/settings/agent-connections` 直接提供远程和本机 stdio 配置，不依赖 `VITE_MCP_AGENT_CONNECTIONS_ENABLED`。
- 服务端新客户端注册能力不依赖 `mcp.oauth.agent-registration-enabled`。底层 OAuth 服务仍按既有 `mcp.oauth.enabled` 配置运行。
- Codex 插件包、工具名称、工具结果契约、已有授权记录和团队配置保持原状。Codex 回归不作为部署前置门槛；部署后可做连接与只读检查，不向团队业务数据写入验收内容。

## 数据库

DBX `Linear-Lite` 连接的 `linear-lite` 数据库已创建并核验以下表：

- `mcp_oauth_refresh_token_family`
- `mcp_oauth_refresh_token`

两表使用 InnoDB；主键、索引及外键核验通过，记录数为 0。本次部署不重复执行数据库写操作。

## 部署要求

1. 以本地工作区为发布源，不从 GitHub 拉取代码。
2. 保留本地无关改动和服务器现有配置，不上传凭据文件。
3. 完成前端与服务端构建，记录 JAR 大小和 SHA-256。
4. 上传临时包并核对服务器端哈希；匹配后保留现网 JAR 回滚副本，再替换并重启服务。
5. 核对服务进程、端口、主页、受保护接口及 OAuth 元数据响应。
6. 检查既有 Codex 连接与工具列表；该检查不作为部署阻断条件，不执行团队数据写入。

## 不在本次服务端部署中的事项

GitHub Connector Release 不随服务端部署创建或发布。Windows、Linux 连接器发行包仍需发行流水线产物；本机源码构建状态不能替代跨平台发行验证。
