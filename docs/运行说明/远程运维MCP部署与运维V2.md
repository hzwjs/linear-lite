# 远程运维 MCP 部署与运维 V2

对应[实施方案 V3](../方案设计/远程运维MCP实施方案V3.md)。远程终端接入现有 Linear Lite Java MCP，不部署旧版 `remote-ops/` 平台。

## 1. 环境

- Java 17 及现有 Linear Lite 服务与 OAuth。
- 控制服务运行于 Linux 或 macOS，能访问外部 ssh-agent 的 Unix socket。
- 目标机提供 SSH、SFTP；PTY、signal 的行为由目标 SSH 服务实现决定。
- 部署方维护目标主机公钥与账号公钥，凭据服务或管理员向 ssh-agent 加载私钥。

MCP 不加载私钥文件、不接收登录密码。签名 socket 是受控能力，socket 权限应限制到服务身份；私钥文件留在凭据服务的文件系统或密钥设备中，不挂载到 MCP 服务。公钥路径与 socket 路径不返回 Agent。

## 2. 服务配置

在部署环境的外部 Spring 配置中加入以下结构；示例不包含认证材料，也不修改仓库内的 application 配置。

```yaml
mcp:
  remote:
    enabled: true
    targets:
      - id: staging
        alias: 测试服务器
        environment: test
        host: server.example.com
        port: 22
        host-key: "ssh-ed25519 <经核对的完整主机公钥Base64>"
        accounts:
          - profile-id: operator
            login-user: operator
            public-key-path: /etc/linear-lite/remote/operator.pub
            agent-socket: /run/credential-agent/agent.sock
            allowed-user-ids: [7]
            max-ttl-seconds: 7200
```

host-key 需替换为经核对的 OpenSSH 公钥文本，禁止使用未核对的扫描值。public-key-path 指向独立公钥文件；agent 中必须存在与其匹配的身份。MCP 不尝试其他身份。

allowed-user-ids 使用 Linear Lite 用户 ID，空列表不授权任何用户。一个 profile 代表一个远端账号与签名身份，其命令与文件权限由远端账号决定。配置变更通过服务重启生效，旧连接句柄失效。

远程能力默认关闭。开启不新增数据库表，不改变业务 MySQL 的结构，不需要对象存储、独立身份服务或目标机 Runtime。

## 3. 客户端授权

远程工具需要 OAuth scope `linear-lite:remote`。现有 read/write 令牌不授予远程账号权限。使用现有连接器的 `--remote` 选项请求远程授权；普通连接器启动维持原授权范围。需要重新授权的客户端应完成包含 remote 的授权流程，禁止通过修改令牌记录伪造授权。

Agent 调用 list_targets 查看获授权的服务器与账号；调用 open_session 建立连接，再使用 exec、read_output、write_stdin 操作终端。

exec 原样传递 command。PTY 可用于交互 shell；需要工作目录、环境变量、执行期限或后台进程时，由 Agent 在服务器端处理。

## 4. 通道与文件

read_output 的 after_seq 从 0 开始，下一次使用 next_seq。truncated=true 表示内存输出缓冲存在缺口，不得据此补造日志。eof=true 但 channel_open=true、退出信息为空时，可使用带 wait_ms 的读取等待退出信息。退出码是命令事实，不是业务验收结论。

write_stdin 的 close action 用于释放通道；close_session 释放整个连接。EOF、signal、关闭连接不承诺远端进程终止。后台进程状态需由 Agent 登录核对。

upload、download 通过 Base64 分块传输，单块上限 256 KiB。路径是远端绝对路径；Agent 的本机文件由自身文件工具读取或写入。upload truncate=true 会在 offset=0 打开文件时截断内容；接口不提供原子发布、快照或重试去重。

服务重启和 SSH 断开会使句柄失效。MCP 不重连、不重发命令、不续接文件操作。Agent 应检查服务器事实后决定后续动作。

## 5. 验证入口

Java 验证使用服务目录中的 Maven focused tests；运行时选择 JDK 17 或兼容编译环境，通过 `-Dexec.skip=true` 跳过与本次后端变更无关的前端构建。

```sh
cd linear-lite-server
mvn -Dexec.skip=true -Dtest='RemoteTerminalServiceTest,RemoteMcpToolsTest,SshRemoteTransportTest,McpDispatcherTest' test
```

连接器使用 `cargo test`、`cargo check` 和 `cargo build --release`。本机集成测试使用临时 SSH/SFTP 与测试身份，不连接业务服务器。部署验收需配置目标、公钥、agent socket 与账号权限后核对真实连接；测试结果不替代该环境证据。

## 实施事项清单

- 配置外部签名服务。
- 配置服务器与账号权限。
- 开启远程工具及 OAuth 授权。
- 核对终端与文件传输。
- 维护主机公钥与账号期限。
