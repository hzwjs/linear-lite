# 远程运维 MCP 团队版 · Codex 接入 V1

本说明面向团队成员：在自己的电脑上，让 Codex 通过团队共享的服务器配置登录服务器。服务器清单、授权和登录证书都由 Linear Lite 云端管理，成员不需要自己的服务器私钥，也不需要把个人公钥放到服务器上。

背景与原理见[团队版方案 V1](../方案设计/远程运维MCP团队版方案V1.md)，管理员的配置步骤见[团队版使用说明 V1](远程运维MCP团队版使用说明V1.md)。

| 项 | 值 |
| --- | --- |
| Linear Lite 地址 | `https://linearlite.eobua.cn` |
| 项目标识 | `EASYGO` |
| 当前可用服务器 | `easygo-test`（root）、`uat-middleware`（user）、`uat-easygo`（user） |
| Codex 版本 | 本文命令在 `codex-cli 0.164.0` 上验证过 |

## 1. 前提

- Node.js 22 或更高：`node -v`。
- 能访问 `https://github.com/hzwjs/linear-lite` 仓库（克隆需要读取权限）。
- 已被项目创建者加入 EasyGo 项目，并被授权到需要的服务器身份。没有授权时，后面 `list_targets` 看不到对应服务器。
- 本机网络能直连 `linearlite.eobua.cn`。如果开着代理（Clash、Shadowrocket 等），请把 `eobua.cn` 设为直连；用 Fake-IP 模式的话，还要把 `*.eobua.cn` 加进 fake-ip 过滤列表，否则域名会被解析成 `198.18.x.x`，登录会失败。

## 2. 安装（一次）

```bash
git clone https://github.com/hzwjs/linear-lite.git
cd linear-lite/remote-terminal-mcp
git checkout AI
npm ci
npm run build
```

构建成功后入口文件是 `dist/src/main.js`。记下它的绝对路径：

```bash
echo "$(pwd)/dist/src/main.js"
```

再记下 Node 的绝对路径，后面要用：

```bash
which node
```

## 3. 登录 Linear Lite（一次，约每月续期）

```bash
node dist/src/main.js login --server https://linearlite.eobua.cn
```

浏览器会打开授权页，用自己的 Linear Lite 账号登录，点「允许访问」，页面显示 `Signed in. You can close this window.` 即成功，终端会打印 `Signed in to https://linearlite.eobua.cn`。

- 令牌保存在 `~/.config/remote-terminal-mcp/credentials.json`，权限 600，请不要拷贝或提交。访问令牌 30 天有效，MCP 会自动用刷新令牌续期；只有长期不用导致刷新令牌也过期时才需要重新登录。
- 授权页只申请 `remote-terminal` 这一项权限，不能读写你的任务和文档。
- 退出登录：`node dist/src/main.js logout --server https://linearlite.eobua.cn`。

## 4. 把 MCP 加到 Codex

把下面命令里的两个路径换成上一步记下的**绝对路径**：

```bash
codex mcp add remote-terminal -- /绝对路径/node /绝对路径/linear-lite/remote-terminal-mcp/dist/src/main.js --server https://linearlite.eobua.cn --project EASYGO
```

一定要写 Node 的绝对路径，不要只写 `node`：从桌面应用启动的 Codex 的 `PATH` 很短，找不到 nvm、Homebrew 里的 `node`，MCP 会启动失败。

命令会在 `~/.codex/config.toml` 里写入下面这一段，也可以直接手工编辑：

```toml
[mcp_servers.remote-terminal]
command = "/绝对路径/node"
args = [
  "/绝对路径/linear-lite/remote-terminal-mcp/dist/src/main.js",
  "--server", "https://linearlite.eobua.cn",
  "--project", "EASYGO",
]
enabled = true
```

检查是否生效：

```bash
codex mcp list
codex mcp get remote-terminal
```

`list` 里应看到 `remote-terminal`、状态 `enabled`；`Auth` 一列显示 `Unsupported` 是正常的，登录由本说明第 3 步的命令行完成，不走 Codex 的 OAuth。

**改完配置后重启 Codex（或开一个新会话），工具目录才会刷新。**

## 5. 使用

在 Codex 里直接用自然语言，例如：

> 使用 remote-terminal，列出我能访问的服务器。

> 登录 easygo-test，执行 `id` 和 `df -h`，只读，不要改任何文件。

MCP 提供 8 个工具，Codex 会自己组合：

| 工具 | 作用 |
| --- | --- |
| `list_targets` | 列出你被授权的服务器和登录身份 |
| `open_session` | 打开一个 SSH 会话（需要指定 `target_id`、`profile_id`、`ttl_seconds`） |
| `exec` / `read_output` / `write_stdin` | 在会话里执行命令、读取输出、发送输入 |
| `upload` / `download` | 上传、下载文件（分块） |
| `close_session` | 关闭会话 |

每次 `open_session` 时，MCP 在本机内存里生成一把临时密钥，向云端申请一张短期证书，再直连服务器。证书有效期等于你请求的会话时长，且不超过该身份的上限（EasyGo 当前是 3600 秒）。会话用完请让 Codex 关闭。

**使用约定**

- 服务器上的操作以登录身份（例如 `easygo-test` 是 root）执行，影响真实环境。涉及删除、重启、改配置的命令，请让 Codex 先说明再执行。
- 每个成员每分钟最多申请 30 张证书，超出会提示稍后再试。
- 管理员能在 Linear Lite 里看到谁在什么时间为哪台服务器申请过证书，但看不到你执行的命令。

## 6. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| Codex 里看不到 `remote-terminal` 的工具 | 重启 Codex；`codex mcp list` 确认 `enabled`；确认配置里 `command` 是 Node 的绝对路径 |
| `Not signed in; run: remote-terminal-mcp login ...` | 令牌过期或还没登录，重新执行第 3 步的命令 |
| `list_targets` 是空的 | 还没有被授权。联系项目创建者，在项目设置 → 远程服务器里勾选你 |
| `Not authorized for this target` | 同上，授权被取消，或你请求了没有授权的身份 |
| `Host key for xxx changed ...` | 服务器的主机公钥被管理员改过。向管理员核对提示里的新指纹，无误后由你本人执行下面的命令，Agent 不能替你做 |
| `Cloud service unavailable` | 网络不通或服务暂不可用。先检查代理的直连设置；云端不可达时 `list_targets` 最多用 24 小时内的本机缓存回答，但不能打开新会话 |
| `SSH connection could not be opened` | 服务器没有接受证书，或连接没有建立。可能的原因：该服务器还没有执行接入脚本；你的电脑和服务器时间相差超过 1 分钟（证书从签发前 60 秒开始生效）；你的网络到不了服务器的 22 端口。出于安全，错误信息不区分原因，请联系管理员在服务器的 sshd 日志里查看 |
| 登录页或授权页打不开，且域名解析成 `198.18.x.x` | 代理的 Fake-IP 劫持了域名，见第 1 步 |

确认主机公钥变更后执行：

```bash
node dist/src/main.js pins accept --server https://linearlite.eobua.cn --project EASYGO 服务器的target_id
```

## 7. 更新

```bash
cd linear-lite/remote-terminal-mcp
git pull
npm ci
npm run build
```

然后重启 Codex。令牌和主机公钥记录在 `~/.config/remote-terminal-mcp/`，更新代码不会丢。

## 8. 卸载

```bash
codex mcp remove remote-terminal
node dist/src/main.js logout --server https://linearlite.eobua.cn
rm -rf ~/.config/remote-terminal-mcp
```

最后一条会同时删除本机记住的主机公钥和目录缓存，之后再用需要重新登录。

## 已知限制

- 只支持 Ed25519 证书，不支持 RSA 证书。
- 云端不经手命令与输出，因此管理员只能审计证书签发，不能审计命令。
- 一个 MCP 进程对应一个云端项目；要同时用多个项目，需在 `config.toml` 里再加一段配置，名字不同、`--project` 不同。
- 已签发的证书在被取消授权后仍可用到过期，最长 3600 秒。
