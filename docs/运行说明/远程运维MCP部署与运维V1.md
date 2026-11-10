# 远程运维 MCP 部署与运维 V1

实现契约：[远程运维 MCP 实施方案 V2](../方案设计/远程运维MCP实施方案V2.md)。代码位于 `remote-ops/`，分为控制服务、目标机 Runtime 和本机连接器，不依赖 Linear Lite 的业务数据库及登录服务。

## 1. 部署组成

| 组件 | 运行位置 | 依赖与职责 |
| --- | --- | --- |
| service | 控制域，Node.js 22 | MCP HTTP、授权、任务账本、SSH Worker、对象回收 |
| signer | 控制域的凭据隔离容器 | 持有 SSH 私钥，向 Worker 暴露签名 socket |
| runtime | Linux 目标机，按登录账号安装 | systemd 托管、PTY、输入去重、输出账本、文件发布 |
| connector | Agent 所在电脑 | stdio MCP、OAuth PKCE、系统凭据库、本地文件桥接 |
| PostgreSQL | 控制域 | 服务器目录、Grant、会话、任务、制品索引与审计 |
| S3 | 控制域 | 不可变分块与制品内容 |
| OAuth IdP | 身份域 | 认证用户、签发委托身份与令牌 |

`remote-ops/deploy/compose.yml` 提供 service 与 signer 容器。PostgreSQL、S3、IdP 和 HTTPS 入口由部署环境提供。数据库、S3 和服务状态目录应纳入备份，恢复时保留任务 ID、nonce 与远端账本。

## 2. 身份服务

注册原生公共客户端，使用授权码与 PKCE S256；允许 `http://127.0.0.1:{动态端口}/callback` 的回环回调。开启 refresh token 轮换；连接器要求授权和刷新应答含 refresh token。授权及 token 端点必须位于配置的 issuer 同源 HTTPS 地址，提供 OAuth authorization-server metadata。

JWT access token 使用 ES256、RS256 或 EdDSA，包含 `iss`、`aud`、`exp`、`iat`、`sub`、`client_id`、`delegation_id` 和包含 `remote-ops` 的 `scope`。`aud` 等于 service 的 `OPS_PUBLIC_URL`，该值不带结尾斜线。`sub`、`client_id`、`delegation_id` 组成授权 Principal；不以 `azp` 或其他字段代替。

IdP 负责委托 ID 的签发与撤销；Grant 的三项 Principal 字段必须与令牌相符。服务校验签名和期限，每次工具调用及制品读取还需当前 Grant。短期 access token 的签发、撤销传播策略由 IdP 管理。

## 3. 构建

在仓库根目录执行：

```sh
cd remote-ops/service
npm ci --ignore-scripts
npm run build
```

```sh
cd remote-ops/runtime
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /tmp/remote-ops-runtime .
```

ARM64 目标使用 `GOARCH=arm64`。连接器在目标平台构建：

```sh
cargo build --locked --release --manifest-path remote-ops/connector/Cargo.toml
```

`.github/workflows/remote-ops-release.yml` 为人工触发的构建工作流，产出 Linux Runtime、连接器平台包及服务构建文件；不执行部署。

## 4. 控制服务配置

| 参数 | 含义 |
| --- | --- |
| `OPS_DATABASE_URL` | 专用 PostgreSQL 连接，由运行环境注入 |
| `OPS_PUBLIC_URL` | HTTPS 服务 origin，不带路径或结尾斜线 |
| `OPS_ISSUER_URL` | JWT issuer，需与令牌一致 |
| `OPS_JWKS_URL` | issuer 同源 JWKS 地址 |
| `OPS_S3_ENDPOINT` | S3 API 地址 |
| `OPS_S3_REGION`、`OPS_S3_BUCKET` | 专用 region 与 bucket |
| `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY` | S3 身份，由运行环境注入 |
| `OPS_STATE_DIR` | Worker 私有暂存目录，容器内 `/var/lib/remote-ops` |
| `OPS_BIND`、`OPS_PORT` | 默认 `127.0.0.1`、`9096`；Compose 内监听 `0.0.0.0` |
| `OPS_PUBLIC_KEYS_DIR` | Compose 挂载的 SSH 公钥目录 |
| `OPS_SIGNING_KEY_FILE` | Compose signer 的私钥源文件路径，置于仓库外 |

bucket 应专用于本服务，关闭版本控制或为非当前版本配置生命周期；授予服务该 bucket 内对象读、写、删、列举及 multipart 权限。配置 S3 生命周期回收未完成 multipart，避免进程崩溃遗留分段。禁止给该 bucket 配置早于服务保留期的对象过期规则。

通过 DBX 将 `remote-ops/service/schema.sql` 安装到空白专用库。该文件包含建表定义；服务启动不执行 DDL，不修改 Linear Lite 的 `schema.sql`。数据库账号的运行权限覆盖这些表及审计 identity 序列；建表身份与运行身份可分离。

通过 DBX 维护三类目录数据：

| 表 | 必填配置 |
| --- | --- |
| `target` | UUID `target_id`，alias、environment、host、port、host_key |
| `profile` | UUID `profile_id`，target_id、login_user、privilege_description、credential_socket、public_key_path、runtime_command |
| `grant_record` | grant_id，Principal 三字段，target_id、profile_id、actions、expires_at、max_session_ttl_seconds、max_run_timeout_ms、max_concurrent_tasks |

`host_key` 为经线下核对的 SSH 主机公钥，格式是 `ssh-ed25519 AAAA…` 等 OpenSSH 公钥格式，不是指纹。`credential_socket` 在示例 Compose 中为 `/run/signers/default.sock`，`public_key_path` 为 `/etc/remote-ops/keys/` 内所选公钥，`runtime_command` 为目标机 Runtime 的绝对路径。每个 Profile 绑定账号与身份；多身份部署需隔离 signer socket 并挂载对应公钥。

actions 取值：`session:open`、`session:close`、`task:create`、`task:read`、`task:input`、`task:cancel`、`file:upload`、`file:download`、`artifact:read`、`artifact:write`。一次操作所需动作应包含于同一 Grant，跨目标上传还需来源制品的当前读取权限。

通过部署平台注入参数，不把凭据写入仓库、文档、shell 历史或任务材料。Compose 的文件 secret 在本机模式使用文件挂载，部署者需确保 signer UID 1000 具有读取权限。Worker 容器不挂载私钥。

```sh
docker compose -f remote-ops/deploy/compose.yml up --build -d
```

HTTPS 入口应保留公共 `Host`，转发 `/mcp`、`/context`、`/artifacts/*` 与 `/.well-known/oauth-protected-resource`；响应不可缓存，读取超时需覆盖 20 秒长轮询。服务拒绝不匹配的 Host 与 Origin。`/health` 表示服务进程可响应，不表示目标机或运维业务验证通过。

## 5. 目标机 Runtime

将对应架构二进制安装到目标机的固定绝对路径，如 `/usr/local/bin/remote-ops-runtime`。目标需要 systemd、SSH、SFTP、`/bin/sh` 及 PTY 支持；Runtime 状态目录由登录账号拥有。root 默认使用 `/var/lib/remote-ops-runtime`，普通账号使用 `$HOME/.local/state/remote-ops-runtime`。

root 使用系统 systemd。普通账号使用用户 systemd；由管理员启用 linger 并准备用户 manager 与用户总线，确保非交互 SSH 可执行 `systemctl --user`。运行以下探针时使用 Profile 的账号：

```sh
printf '%s' '{"protocol":1,"method":"capabilities","payload":{}}' | /usr/local/bin/remote-ops-runtime rpc
```

探针成功不替代 systemd 任务启动、停止和文件发布验证。SSH 账号权限决定远端文件与命令权限；root 包含读取业务凭据、变更系统服务的能力。Runtime 的回收保证覆盖所建 cgroup，无法约束 root 命令创建其他服务等越界行为。

`remote-ops/deploy/remote-ops-prune.service` 与 `.timer` 每小时调用 `/usr/local/bin/remote-ops-runtime prune`。清理器处理停止且超过七天的任务数据，保留账本、启动门和结果；未知任务保留数据。root 将这两个文件安装到 `/etc/systemd/system/`，执行 `systemctl daemon-reload` 和 `systemctl enable --now remote-ops-prune.timer`；普通账号安装到 `~/.config/systemd/user/`，使用 `systemctl --user` 执行相同操作。自定义 Runtime 路径或状态目录时，修改 unit 的 ExecStart 与 Environment。

## 6. 本机连接器

连接器依赖操作系统凭据库：macOS Keychain、Windows Credential Manager，或 Linux Secret Service。凭据库不可用时应修复系统服务，不把令牌落入 JSON 配置。

MCP 客户端配置示例（路径及公共域名替换为部署值）：

```json
{
  "mcpServers": {
    "remote-ops": {
      "command": "/opt/remote-ops/remote-ops-connector",
      "args": [
        "--config",
        "{\"service_url\":\"https://ops.example.com\",\"issuer_url\":\"https://identity.example.com\",\"client_id\":\"remote-ops-native\",\"allowed_local_roots\":[\"/Users/operator/work\"],\"state_dir\":\"/Users/operator/.remote-ops\"}"
      ]
    }
  }
}
```

`--config` 接收 JSON 字符串。`allowed_local_roots` 为允许文件桥接的绝对目录；`state_dir` 为私有 SQLite 与暂存目录，两者不重叠，目录须存在或可创建。首次工具发现触发浏览器授权。连接器以 stdio 提供 12 个远端工具和 4 个本地工具，不启动本地 HTTP 文件代理。

本地上传先 `stage_local_file` 取得 artifact_id，再调用远端 `upload`。远端 `download` 完成后，通过 `get_artifact` 查询元数据，再 `fetch_artifact` 写入本地。`get_local_transfer` 与 `cancel_local_transfer` 管理本地传输，`get_task` 与 `cancel_task` 管理远端任务。

## 7. 生命周期与维护

变更请求携带 UUIDv7 `request_id`。首次接收窗口为 24 小时，去重记录保留 30 天且覆盖关联活动任务。重试必须保留 ID 与参数；使用新 ID 会创建新操作。连接器的创建和完成应答丢失恢复复用原请求或原 upload_id；本地发布结果未知时保留记录，禁止覆盖重放。

Worker 以数据库租约与递增 epoch 接管，远端任务使用固定 task_id、nonce、规格哈希。连接丢失返回 unknown/reconciling，控制服务恢复后核对原任务，不重新执行 Agent 命令。stdin 使用 input_seq 与 Runtime 账本；`input_unknown` 表示写入结果不能确认，禁止用新序号掩盖。

取消应答表示停止意图持久化。终态需 Runtime 启动门或 systemd stop-post 证据；结果丢失时保留 `result_availability=lost`。命令非零退出仍是 completed 的执行结果，排障是否成功由 Agent 判断。文件发布或操作完成事件先于停止生效时保留 completed。

输出与下载制品保留至任务终态后七天，本地上传制品保留七天，桥接上传到来源会话结束或创建后 24 小时。服务每分钟回收过期索引，并分页扫描超过 24 小时的对象；无活引用时先写数据库墓碑，再删对象。新引用检查同一墓碑，防止旧 Worker 迟到发布丢失对象。S3 删除失败会留待后续扫描。Runtime 的 prune 负责目标机任务数据，元数据与未知状态任务不按时间推断终态。

审计表记录身份、授权范围、请求哈希、任务变化及制品引用，不保存命令输出或认证材料。运维查询通过 DBX 执行；令牌过期、Grant 撤销或权限动作移除后，内容读取重新判权，传输中的读取与 SFTP 由监测器中断。停止 SSH 连接不能证明目标进程停止，仍需核对 Task.end。

## 8. 验证入口与交付边界

```sh
cd remote-ops/service
npm test
npm run typecheck
npm run build
```

跨语言契约验证需先为当前系统构建 Runtime，再设置 `OPS_RUNTIME_TEST_BINARY` 并运行 `npm run test:runtime-contract`。Go 验证使用 `go test -race ./...` 与 `go vet ./...`；连接器使用 `cargo test` 与 `cargo build --release`。

本机协议测试覆盖签名 JWT、Host、协议版本、工具列表、调用与错误结构；Worker 测试覆盖下载候选提交与传输中断。模拟数据库测试不能代替 PostgreSQL 事务验证。部署验收还需空白库 schema、权限撤销与竞争事务、S3 故障恢复、目标机 systemd 与真实 OAuth 贯通的证据。该文档不将构建或健康探针作为这些行为的完成证明。

## 实施事项清单

- 部署专用数据库、S3 与身份服务。
- 安装 signer、控制服务与 Runtime。
- 配置目标目录、账号与 Grant。
- 接入本机连接器与文件目录。
- 配置备份、回收与监测。
- 执行部署环境验收。
