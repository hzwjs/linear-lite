# 远程运维 MCP 团队版使用说明 V1

对应[团队版方案 V1](../方案设计/远程运维MCP团队版方案V1.md)。云端（Linear Lite）保存服务器配置并签发短期 SSH 证书，SSH 流量仍由成员本机直连目标服务器。

## 1. 部署云端（一次）

| 项 | 要求 |
| --- | --- |
| `RT_CA_KEK` | `openssl rand -base64 32` 生成，写入服务端环境变量。缺失或长度不对时，远程终端整体不可用。丢失后已有 CA 无法解密，需重新创建 CA 并重新接入全部服务器。 |
| `MCP_ENABLED`、`MCP_OAUTH_ENABLED` | 必须为 true，本机 MCP 通过 OAuth 登录。 |
| `MCP_OAUTH_ISSUER` | 必须是 HTTPS 源地址，成员用它作为 `--server`。 |
| 数据库 | 全新库执行 `schema.sql`；已有库执行 `migration-add-remote-terminal.sql`（均为幂等）。 |

## 2. 项目创建者配置

在项目设置的“远程服务器”中操作：

1. 创建 CA。
2. 添加服务器：地址、端口、完整主机公钥。主机公钥在服务器控制台取得（`/etc/ssh/ssh_host_ed25519_key.pub`），并核对页面显示的指纹。
3. 为服务器添加登录身份（登录账号、最长连接秒数）。同一服务器上每个账号只能有一个身份。
4. 勾选允许使用该身份的项目成员。新增的服务器默认没有任何人可用。
5. 点“接入脚本”，审阅后在该服务器上以 root 执行一次。脚本只加入本项目的 CA 与 principal，原有的 `authorized_keys` 登录不受影响；`sshd -t` 失败或别处已设置 `TrustedUserCAKeys` 时会自动回滚。新增或删除登录身份后需要重新执行。

## 3. 成员使用

```bash
remote-terminal-mcp login --server https://你的-linear-lite
```

浏览器登录并同意 `remote-terminal` 授权后，把 MCP 启动命令改为：

```bash
node /path/to/remote-terminal-mcp/dist/src/main.js --server https://你的-linear-lite --project ENG
```

之后 `list_targets` 只显示自己被授权的服务器；每次 `open_session` 在本机内存中生成临时密钥并向云端申请证书，有效期等于连接期限。

## 4. 主机公钥变化

本机首次成功登录后会记住服务器的主机公钥。项目创建者修改主机公钥后，成员的客户端会拒绝连接，并提示变化前后的指纹。核对无误后由成员本人执行：

```bash
remote-terminal-mcp pins accept --server https://你的-linear-lite --project ENG 服务器的target_id
```

Agent 无法替成员执行这一步。

## 5. 撤销与轮换

| 场景 | 做法 | 生效 |
| --- | --- | --- |
| 取消某成员对某身份的授权 | 取消勾选 | 立即不能再申请证书；已签发的证书最多再用到过期（不超过该身份的最长连接秒数） |
| 需要立刻切断 | 在服务器上删除 `/etc/ssh/linear-lite/principals/<账号>` 中对应的行 | 立即 |
| 轮换 CA | “轮换 CA” → 所有服务器重新执行接入脚本 → “结束旧 CA” → 所有服务器再次执行脚本 | 按步骤 |

## 6. 已知限制

- 证书只支持 Ed25519，不支持 RSA 证书。
- 云端不经手命令与输出，因此“签发记录”只能回答谁在何时为哪台服务器的哪个账号申请了证书，不记录执行的命令。
- 云端暂时不可达时，`list_targets` 使用本机缓存（24 小时内）回答，但不能打开新会话。
- 离线缓存只用于列表，不用于授权连接。
