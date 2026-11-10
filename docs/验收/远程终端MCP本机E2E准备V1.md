# 远程终端 MCP 本机 E2E 准备 V1

日期：2026-10-26。对应[部署说明 V3](../运行说明/远程运维MCP部署与运维V3.md)。

## 本机环境

| 项目 | 当前值 |
| --- | --- |
| Node | v22.19.0 |
| Node 命令 | `/Users/huangzhiwen/.nvm/versions/node/v22.19.0/bin/node` |
| MCP 入口 | `/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/remote-terminal-mcp/dist/src/main.js` |
| 目标配置 | `/Users/huangzhiwen/.config/remote-terminal/targets.json`，权限 600 |
| Codex MCP 名称 | `remote-terminal`，stdio，enabled |
| target_id | `e2e-124-223-84-101` |
| profile_id / 账号 | `root` |
| 服务器 | `124.223.84.101:22` |
| 环境标识 | `unspecified`，服务器环境属性未由用户指定 |
| 最大连接期限 | 7200 秒 |

配置使用本机现有部署公钥与系统 ssh-agent。登录身份由系统钥匙串解锁并加载到 agent；MCP 不读取私钥。主机公钥取自本机已有 known_hosts 记录。目标配置不包含密码。

## 准备证据

- `npm ci` 完成，依赖审计返回 0 项漏洞。
- TypeScript 构建完成。
- CLI 子进程通过 stdio 完成 server/discover、tools/list、list_targets。
- 工具数量为 8，目标数量为 1。
- open_session 使用该目标与 root 身份完成公钥认证，随后 close_session 返回 closed=true。
- `codex mcp get remote-terminal --json` 返回 enabled=true 与对应 Node 命令、入口、配置路径。

本轮检查连接就绪状态，没有执行远端命令、文件上传下载或业务变更；这些步骤留给用户的 E2E。

## 用户 E2E 入口

当前会话的工具目录未加载新 MCP。重新加载 MCP 或重启 Codex 后，确认工具目录中存在 remote-terminal 的 8 个工具。

可发送以下请求：

> 使用 remote-terminal MCP，对 target_id=e2e-124-223-84-101、profile_id=root 执行 E2E。建立会话，执行 id 和 pwd；在新建的 /tmp 临时目录内，通过 MCP 上传与下载一个测试文件，核对内容和 SHA-256；清理该临时目录，关闭通道与会话。不得变更业务文件或服务。报告每一步的证据。

配置中的 agent_socket 是当前登录环境的路径。agent 重启或登录环境改变后，需要更新该字段并重启 MCP。

## 实施事项清单

- 安装依赖与构建入口。
- 配置目标和公钥身份。
- 注册 Codex stdio MCP。
- 核对协议发现与 SSH 认证。
- 准备用户 E2E 操作入口。
