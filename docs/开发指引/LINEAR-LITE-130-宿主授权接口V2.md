# LINEAR-LITE-130 宿主授权接口 V2

## OAuth 客户端

宿主通过 `POST /oauth2/register` 注册公共客户端，注册资料包含：

| 字段 | 值 |
|---|---|
| `client_name` | 宿主应用名称 |
| `redirect_uris` | 一个 `http://127.0.0.1:<端口>/<路径>` 回环地址 |
| `grant_types` | `authorization_code`、`refresh_token` |
| `token_endpoint_auth_method` | `none` |
| `scope` | `collaboration-host` |
| `device_id` | 本机设备生成的小写 UUID |
| `device_name` | 设备展示名称 |

授权请求使用 `resource=<issuer>/api/collaboration-host`、`scope=collaboration-host` 和 S256 PKCE。授权确认页展示账号、设备名称与设备标识。服务端按授权码、PKCE 与用户确认后的 OAuth authorization 建立设备授权；客户端在回环回调校验预期 `state` 的实现归 LINEAR-LITE-133。

授权码兑换与刷新请求携带相同 `resource`。刷新令牌轮换复用授权记录，重授权建立新记录并撤销该用户与设备的旧记录。宿主 scope 不与 MCP 或远程终端 scope 混合；OAuth 访问令牌不能用于网页登录 API。

## 授权状态、心跳与撤销

| 接口 | 认证 | 行为 |
|---|---|---|
| `GET /api/collaboration/hosts` | 网页用户 | 查询当前用户的设备授权历史、授权状态与在线状态 |
| `DELETE /api/collaboration/hosts/{id}` | 网页用户 | 撤销本人设备授权，重复撤销保持同一结果 |
| `POST /api/collaboration-host/heartbeat` | 宿主 OAuth 访问令牌 | 更新认证关联授权的心跳，不从请求体读取设备标识 |
| `POST /api/collaboration-host/recovery/*` | 原批次签名与执行证明 | 停止恢复专用入口，允许原批次签名过期；不建立普通用户写入身份 |

响应使用 `Cache-Control: no-store`。`lastHeartbeatAt` 与 `createdAt` 为 UTC 时间，输出带 `Z` 的时间；距离服务端心跳超过 90 秒或授权撤销时，`online` 为 `false`。授权或心跳响应不包含 OAuth 令牌。

服务器校验令牌签名、issuer、audience、scope、OAuth 授权与设备授权归属。普通宿主接口区分授权撤销与访问令牌过期；无效签名、用途不符和账号不符的请求拒绝。

## 批次与停止恢复的服务契约

`HostDeviceAuthorizationService.requireOnlineGrant(userId, deviceId)` 在调用方事务内锁定有效授权，核对设备精确匹配及在线心跳。`findByIdForUser(userId, grantId)` 按用户读取授权；`response(grant)` 生成状态 DTO。批次应保存授权记录主键，重授权不会使旧批次转用新授权。

LINEAR-LITE-132 删除 `recovery-identity`；恢复使用原批次签名作为唯一能力来源，不保存历史 OAuth 访问令牌。恢复入口还核对原设备授权、宿主实例、执行代次及原任务领取证明；原批次签名可过期，恢复不授予任务领取、续租或成果写入能力。详细契约见 `LINEAR-LITE-132-宿主批次执行与停止恢复接口V1.md`。授权撤销或同设备重新授权会在同一事务提交旧授权关联批次的停止意图，资源保留到宿主确认停止。
