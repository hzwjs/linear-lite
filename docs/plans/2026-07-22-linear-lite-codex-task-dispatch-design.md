# Linear Lite 派发本机 Codex App 任务设计

- 更新日期：2026-07-23
- 状态：实施中
- 涉及模块：任务详情、`linear-lite-server`、`linear-lite-codex-runner`、macOS Codex Desktop Driver

## 一、目标与边界

本期只解决“在 Linear Lite 创建并指派给 Codex 的任务，立即交给本机 Codex App 执行”。任务由任务详情页的唯一 `taskKey` 派发，不存在自然语言搜索、语义检索或公共领取会话。

必须满足的交互约束：

- 一条 Linear Lite 任务对应一条独立 Codex App 会话；
- Runner 只运行在拥有仓库与 Codex 登录态的开发者本机；
- 创建 worktree 后，Codex App 使用该 worktree 和本次专属分支；
- 任务创建后由本机自动发送，不要求用户再点击“发送”；
- 不使用后台公共 Codex 会话领取多个任务；
- 不依赖退出或重启 Codex App 才显示会话。

本期不做反向同步：Codex App 的最终回答、推理过程和 `needs_input` 不回传到 Linear Lite。Linear Lite 的本期职责是可信地记录“已成功启动桌面会话”，实际代码执行过程与结果以该独立 Codex App 会话为准。

## 二、为什么采用 Desktop Driver

Codex App 的公开 Deep Link 能创建本地新会话并预填任务内容，但不会自动发送；App Server 创建的外部会话又不能稳定地实时出现在桌面端项目中。因此 Runner 不再调用 App Server。

Runner 改为启动本机 `LinearLiteCodexDesktopDriver`。它是一个由 Swift 编译的原生桌面驱动，固定以 macOS ScreenCaptureKit + Vision 和辅助功能 API 操作指定的 Codex Desktop 应用：

1. 打开项目根目录的新会话 Deep Link；
2. 仅在内存中识别该 Codex 窗口的可见文字，确认输入框中含本次运行唯一的 activation marker；
3. 点击会话底部已验证的仓库标签，在 Codex 自己的 UI 中选择本次创建的 worktree / 分支；
4. 再次确认 marker 未变化后发送 Return；
5. 确认 marker 已离开输入框，才将 Linear Lite 执行置为 `running`。

Driver 只会对配置中的一个 bundle identifier 操作。找不到输入框、工作树选择器、目标 worktree，或无法确认发送时，立即失败并不发送到其他窗口或会话。

### 2.1 当前设备验证结论

2026-07-23 在本机已打开的 `com.openai.codex` 上确认，Accessibility 树只暴露系统窗口按钮，不足以安全定位会话内容。Driver 已切换为 ScreenCaptureKit + Vision；屏幕内容只在本机内存中用于定位 activation marker、仓库标签和分支文本，不写入文件、不上传服务器。

首次使用需要授予“辅助功能”和“屏幕录制”权限。任一权限缺失时 Driver 失败退出，不会发送任务。

## 三、整体流程

```text
任务详情将负责人设为 Codex / 派发本次任务
                ↓
Linear Lite Server 创建携带固定 taskSnapshot 的 queued run
                ↓
本机 Runner 原子领取（taskKey 是唯一定位信息）
                ↓
Git fetch → 专属分支 → 专属 worktree
                ↓
Codex Desktop Driver 通过 codex:// Deep Link 打开独立会话
                ↓
验证 activation marker → 选择 worktree → 自动发送
                ↓
Codex App 在本机项目中执行；Linear Lite 将 run 标记为 running
```

`activationMarker` 固定为：

```text
linear-lite-run:<run-id>
```

它来自运行 ID，既用于确认“发送的是本次任务”，也避免通过任务标题做模糊识别。

## 四、部署边界

| 部署面 | 保存内容 | 行为 |
| --- | --- | --- |
| Linear Lite 服务端 | 任务快照、Runner Token 哈希、执行状态 | 创建/领取运行、鉴权、记录桌面会话启动事件 |
| 本机 Runner | 仓库白名单、Git 凭据、Codex Desktop、Runner Token | 创建 worktree、驱动本机 Codex App |

服务端不保存 Codex 凭据、不访问开发机文件，也不触发开发机入站请求。Runner 只通过 HTTPS 轮询服务端。

## 五、Runner 运行时设计

### 5.1 固定配置

Runner 配置必须包含：

```json
{
  "serverUrl": "https://linear-lite.example.com",
  "runnerToken": "仅保存在本机",
  "stateDirectory": "/Users/me/.local/state/linear-lite-codex-runner",
  "codexDesktopAppBundleIdentifier": "com.openai.codex",
  "codexDesktopLaunchTimeoutSeconds": 30,
  "repositories": {
    "linear-lite": {
      "path": "/Users/me/code/linear-lite",
      "remoteIdentity": "github.com/example/linear-lite.git",
      "defaultBranch": "main"
    }
  }
}
```

`codexDesktopAppBundleIdentifier`、超时时间和仓库白名单均为必填项；不存在配置回退或多路径查找。

### 5.2 启动一次任务

1. 只对状态为 `claimed` 的 run 创建新 worktree；
2. 分支固定为 `codex/<task-key-lowercase>-<run-id-short>`；
3. Runner 将本地状态保存为 `runId + worktreePath + branchName + activationMarker`；
4. Driver 使用项目根目录建立会话归属，在 App UI 内选择 worktree；
5. 发送成功后调用 `PUT /api/codex-runner/runs/{runId}/started`，再上报 `desktop_session_started`；
6. 已处于 `running` 的 run 只续租，永远不再次打开或发送会话。

因此 Runner 重启后不会重复派发同一任务。

### 5.3 macOS 权限

首次运行前，用户必须在“系统设置 → 隐私与安全性 → 辅助功能”中授权本机固定路径的 Driver：`~/.config/linear-lite-codex-runner/state/bin/linear-lite-codex-desktop-driver`。可先执行下列命令唤起系统授权提示；Driver 未获授权时返回 `CODEX_ACCESSIBILITY_PERMISSION_REQUIRED`，任务失败但保留 worktree，绝不模拟输入。

```bash
npm run start -- /absolute/path/to/runner.json request-desktop-access
```

排查 UI selector 时可执行：

```bash
npm run start -- /absolute/path/to/runner.json inspect-desktop
```

它只输出当前 Codex Desktop 窗口中本地 OCR 识别到的可见文字，不会新建或发送任务。

## 六、服务端状态与接口

```text
queued → claimed → running
              └→ failed
```

`running` 表示本机 Driver 已确认将本次任务发送至独立 Codex App 会话，不表示服务端掌握了模型的最终输出。这个状态不会被 Runner 重新派发。

Runner 增加唯一启动确认接口：

```http
PUT /api/codex-runner/runs/{runId}/started
```

服务端仅允许 `claimed → running`；重复调用在 `running` 时幂等返回。Runner 随后以序号 `1` 上报：

```json
{
  "eventType": "desktop_session_started",
  "eventPayload": {
    "activationMarker": "linear-lite-run:<run-id>",
    "branchName": "codex/<task-key>-<run-id-short>"
  }
}
```

不保存或伪造 Codex 内部 thread ID。桌面 Deep Link 没有返回该 ID；把 run ID 写成 thread ID 会破坏数据语义。

## 七、错误处理

| 错误码 | 含义 |
| --- | --- |
| `REPOSITORY_NOT_REGISTERED` | Runner 本地白名单中不存在目标仓库 |
| `GIT_FETCH_FAILED` | 无法获取基础分支 |
| `WORKTREE_CREATE_FAILED` | 无法创建隔离工作区 |
| `CODEX_DESKTOP_DRIVER_UNAVAILABLE` | 未安装或无法编译 macOS 原生 Driver |
| `CODEX_ACCESSIBILITY_PERMISSION_REQUIRED` | 未授予辅助功能权限 |
| `CODEX_DESKTOP_COMPOSER_NOT_FOUND` | Deep Link 后未找到包含本次 marker 的输入框 |
| `CODEX_WORKTREE_SELECTOR_NOT_FOUND` | 未找到 Codex 的工作树选择器 |
| `CODEX_WORKTREE_NOT_FOUND` | 工作树选择器中没有本次 worktree 或分支 |
| `CODEX_DESKTOP_SEND_NOT_CONFIRMED` | 未能确认本次任务已发送 |

所有失败都保留 worktree，便于检查；Driver 不做点击重试，也不会向其他会话发送内容。

## 八、验收标准

1. 在 Linear Lite 派发一个小范围任务；
2. 不触碰 Codex App 输入框或发送按钮；
3. 30 秒内出现一条包含唯一 `linear-lite-run:<run-id>` 的独立 Codex App 会话；
4. 该会话处于绑定项目，选择的 worktree 与 Linear Lite run 分支一致；
5. 刷新或重启 Runner 不产生第二条会话；
6. 撤销辅助功能权限后，派发失败并且没有任何内容被发送；
7. Linear Lite 记录 `desktop_session_started`，但不显示伪造的 thread ID 或最终结果。
