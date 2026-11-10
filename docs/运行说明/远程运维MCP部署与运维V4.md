# 远程运维 MCP 部署与运维 V4

对应[实施方案 V4](../方案设计/远程运维MCP实施方案V4.md)。MCP 在 Agent 所在机器运行；目标服务器提供 SSH、SFTP。部署不使用 Linear Lite Java 服务、OAuth 或数据库。

## 1. 本机准备

需要 Node.js 22 或以上版本、npm、使用 ssh-agent 时需要支持 Unix socket 的环境（Linux、macOS 或相应 Linux 环境）。

在仓库根目录构建：

```sh
npm --prefix remote-terminal-mcp ci
npm --prefix remote-terminal-mcp run build
```

入口为 `remote-terminal-mcp/dist/src/main.js`。发布时携带该目录的 package.json、package-lock.json 和 dist，在运行机器执行 `npm ci --omit=dev` 安装运行依赖。无需 JAR、Rust 连接器或服务端部署。

## 2. 身份与目标

登录身份支持私钥文件或 ssh-agent。使用 ssh-agent 时，由凭据服务或管理员加载登录身份。使用 ssh-agent 时，私钥留在凭据服务或密钥设备中，MCP 使用公钥选择身份；私钥内容不写入配置、Agent 提示或日志。

可用以下命令核对当前签名环境：

```sh
printf '%s\n' "$SSH_AUTH_SOCK"
ssh-add -l
```

将签名 socket 的实际绝对路径写入配置。程序不读取 SSH_AUTH_SOCK 作为回退；agent 重启并改变 socket 路径时，更新配置并重启 MCP。使用隔离签名服务时，其 socket 需允许 MCP 的 OS 身份访问，私钥文件不挂载给 MCP。

私钥文件模式使用未加密的私钥文件，配置 `private_key_path` 指向文件的绝对路径，代替 `public_key_path` 与 `agent_socket`。两种方式不能混用。限制私钥文件访问权限，例如设置为 600；私钥内容不写入配置、提示或日志。带口令的私钥通过 ssh-agent 使用。

目标账号的 authorized_keys 需包含对应登录公钥；通过目标机控制台取得并核对 SSH 主机公钥。主机公钥与登录公钥是两项配置，不能互换。

## 3. 配置文件

在仓库外保存 `/Users/operator/.config/remote-terminal/targets.json`，替换示例中的目标、公钥及路径：

```json
{
  "targets": [
    {
      "target_id": "staging",
      "alias": "测试服务器",
      "environment": "test",
      "host": "server.example.com",
      "port": 22,
      "host_key": "ssh-ed25519 <完整主机公钥Base64>",
      "profiles": [
        {
          "profile_id": "operator",
          "login_user": "operator",
          "public_key_path": "/Users/operator/.ssh/operator.pub",
          "agent_socket": "/实际路径/agent.sock",
          "max_ttl_seconds": 7200
        }
      ]
    }
  ]
}
```

私钥文件模式的 profile 示例：

```json
{
  "profile_id": "operator",
  "login_user": "operator",
  "private_key_path": "/Users/operator/.ssh/operator",
  "max_ttl_seconds": 7200
}
```

Linux 使用对应的本机绝对路径。公钥文件不能指向私钥，配置不展开 `~` 或环境变量。target_id 在目录中唯一，profile_id 在其目标中唯一。targets=[] 可用于启动一个空目录 MCP。

文件访问权限决定谁可以修改该进程的服务器白名单；配置由本机用户维护，可设置目录权限 700、文件权限 600。MCP 不提供用户授权管理页面，也不约束拥有相同 OS 文件权限的其他程序。

## 4. Agent 接入

将客户端 MCP 启动命令设置为 Node 的绝对路径，参数为构建入口和配置文件。以下为采用 mcpServers 格式的客户端示例：

```json
{
  "mcpServers": {
    "remote-terminal": {
      "command": "/绝对路径/node",
      "args": [
        "/项目绝对路径/remote-terminal-mcp/dist/src/main.js",
        "--config",
        "/Users/operator/.config/remote-terminal/targets.json"
      ]
    }
  }
}
```

客户端使用其他配置格式时，保持 command 和 args 的取值。客户端启动子进程并通过 stdio 通信，无需 URL、端口、OAuth、`--remote` 或浏览器授权。

MCP 协议版本为 2026-07-28，客户端需支持该版本。stdout 是协议通道，诊断写入 stderr。终端中启动程序后等待输入属于 stdio 服务行为。

## 5. 使用

调用顺序：

1. list_targets 查看本机配置的服务器与账号。
2. open_session 选择 target_id、profile_id 和连接期限。
3. exec 原样提交命令，read_output 获取输出与退出事实。
4. 需要交互时，使用 write_stdin 的 data、eof、resize、signal。
5. 操作结束，使用 write_stdin 的 close 释放通道，再 close_session 释放连接。

文件通过 upload、download 分块传输，每块上限 256 KiB；stdin data 上限 64 KiB。Base64 使用规范填充格式。upload 接受空内容，truncate=true 会在 offset=0 时截断文件。

eof 只描述输出流。channel_open=true 且退出信息为空时，使用带 wait_ms 的 read_output 等待控制信息；不补造退出码。truncated=true 表示内存缓冲丢弃了旧输出。

连接中断后句柄失效。Agent 应检查服务器事实，决定是否重试命令或文件操作；MCP 不重放操作，也不承诺断开连接后进程停止。

## 6. 验证入口

```sh
npm --prefix remote-terminal-mcp test
npm --prefix remote-terminal-mcp run typecheck
npm --prefix remote-terminal-mcp run build
```

本机 SSH 集成测试需要 ssh-agent、ssh-add 和 ssh-keygen，使用临时身份与本机 SSH/SFTP 测试服务，不接入业务服务器。协议测试覆盖 stdio 发现、工具调用、错误和关闭路径；这些证据不替代业务服务器的连接验收。

## 实施事项清单

- 安装 Node 与运行依赖。
- 配置私钥文件或外部签名身份。
- 保存本机服务器目录。
- 配置 Agent stdio 命令。
- 核对 SSH 与文件传输。
