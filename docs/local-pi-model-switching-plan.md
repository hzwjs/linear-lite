# 本地 Pi 模型切换支持方案

## 1. 方案结论

模型设置收敛到任务详情的本地 Pi 输入框：在输入框左下角展示“当前模型 + 推理强度”的组合胶囊，点击后弹出紧凑的两级设置菜单。用户在一级菜单选择“模型”或“推理强度”，在二级菜单选择具体值；选择只在本轮空闲时可操作，确认后立即写入当前 Pi session，并作用于后续所有轮次。

不新增项目级、用户级或服务端模型配置。模型目录、认证状态和模型选择都属于执行者本机 Pi；Linear Lite 只负责将当前任务负责人发起的模型读取和切换请求安全地送达已绑定的 Bridge。

```text
Pi 本机模型目录
  -> Bridge get_available_models
  -> PiSettingsState（仅内存 / SSE）
  -> PiTurnComposer 当前设置胶囊
  -> 用户选择模型或推理强度
  -> Bridge set_model / set_thinking_level
  -> Pi session model_change entry / 当前 runtime 配置
  -> 后续 prompt 使用该模型
```

模型状态和推理强度的唯一事实源是 Pi RPC `get_state()`；模型目录的唯一事实源是 Pi RPC `get_available_models()`，推理强度目录的唯一事实源是 `get_available_thinking_levels()`。前端、服务端和 Bridge 不保存模型副本、默认模型、推理强度或兼容字段。

## 2. 交互设计

`PiTurnComposer` 左下角使用截图同款的浅灰组合胶囊，例如 `Claude Sonnet 4.5　中　⌄`；它与语音、发送按钮处于同一输入区底栏，不额外增加设置图标、标题或说明文字。模型名称过长时省略，悬停展示 `provider / modelId`；推理强度显示为“关闭、低、中、高、极高、最大”等 Pi 返回的中文映射。

点击胶囊后，在其正上方打开与截图一致的白色浮层：圆角 18px、轻阴影、单列信息行、右侧箭头。一级菜单只保留两个真实可控项：

- `模型`：右侧显示当前模型的简短名称；进入二级菜单后按 provider 分组展示 Bridge 返回的可用模型，当前项使用勾选标记。
- `推理强度`：右侧显示当前等级；进入二级菜单后展示 Pi 返回的可用等级，当前项使用勾选标记。

浮层没有“速度”“重置为默认设置”“管理模型”或手动输入项。Pi RPC 没有与 Codex 速度、默认配置对应的稳定控制接口，保留这些视觉元素会制造无法兑现的设置。

面板打开后立即读取当前 Pi 设置，以保证胶囊始终显示真实值；该读取会启动一次空闲 Pi RPC runtime，但不创建 Job、不发送 prompt。模型目录和推理强度目录随这次读取一起返回，并仅在当前面板生命周期内使用。关闭后重新打开时重新读取，确保显示终端中可能已经切换的真实设置。

`waiting_input` 是唯一可切换状态。执行中、提交中、取消中和 Bridge 重连中，胶囊保留当前值但禁用，避免中途改变正在运行的 Pi runtime。选择后入口显示小型加载状态；Bridge 返回成功的 `PiSettingsState` 后才更新胶囊并关闭浮层。失败时保留原值并在输入框上方显示真实错误。

首次打开面板时的设置读取会创建 Pi session；它不创建 Job、不发送 prompt。首次选择模型会在该 session 写入 `model_change`，首次选择推理强度会写入 `thinking_level_change`；后续 prompt 沿用这些 session 设置。

## 3. 状态与边界

```text
面板打开
  -> waiting_input
  -> loading_settings
  -> settings_ready
  -> 打开设置菜单
  -> 选择模型或推理强度
  -> applying_setting
  -> settings_ready

running / submitting / canceling / reconnecting
  -> 模型入口禁用
```

- 切换范围固定为一个 `executionId` 对应的 Pi session，不影响其他任务、项目或终端 session。
- 一个 execution 同时最多一个设置控制请求；重复点击或重复选择不创建第二个请求。
- `set_model` 成功后，Pi 写入的 `model_change` 进入 session 历史，但继续作为终端控制元数据，不渲染为对话消息。
- 当前任务负责人是唯一可读取或切换该 execution 模型的用户；Bridge 只能处理其已绑定 execution 的控制请求。
- Bridge 离线、Pi 无可用模型/推理强度或 Pi 拒绝切换时，直接返回该原因；不降级到服务端预设、上次缓存或另一个 provider。

## 4. 服务端与 Bridge 协作

复用 session 快照的“服务端内存请求 + Bridge 主动领取 + SSE 回传”模式，新建 `PiSettingsRequestService`。请求不落库，完成、超时或 Bridge 重启后即释放；Pi session 是唯一需要持久化的模型记录。

### 4.1 前端接口与 SSE

```text
POST /api/tasks/{taskKey}/local-pi/sessions/{executionId}/settings-requests
body: { action: "read" }

POST /api/tasks/{taskKey}/local-pi/sessions/{executionId}/settings-requests
body: { action: "set_model", provider: "...", modelId: "..." }

POST /api/tasks/{taskKey}/local-pi/sessions/{executionId}/settings-requests
body: { action: "set_thinking_level", level: "..." }

GET /api/tasks/{taskKey}/local-pi/sessions/{executionId}/stream
event: pi-settings-state
```

`pi-settings-state` 固定结构：

```ts
interface PiSettingsState {
  executionId: string
  current: {
    model: { provider: string; modelId: string; label: string }
    thinkingLevel: string
  }
  models: Array<{ provider: string; modelId: string; label: string }>
  thinkingLevels: string[]
}
```

`label` 由 Bridge 从 Pi 返回模型对象的显示名称生成；没有显示名称时使用该模型对象的 `id`。此转换只发生在 Bridge 边界，前端不再拼接或猜测名称。

`TaskAgentSessionController` 校验任务访问、负责人和 `executionId` 归属后创建请求。请求进入同一个 execution 的 SSE 通道；前端只接受自身 `executionId` 的 `pi-settings-state`。

### 4.2 Bridge 控制链路

Bridge 主循环在领取普通 Job 与 session 快照请求之间领取设置控制请求：

```text
POST /api/bridge/settings-requests/claim
POST /api/bridge/settings-requests/{requestId}/complete
POST /api/bridge/settings-requests/{requestId}/fail
```

Bridge 使用请求携带的 `projectId`、`executionId` 与 `piSessionId` 调用现有 `resolveTaskDirectory()` 和 `startPi()`。执行顺序固定为：

1. 启动当前 session 的 Pi RPC runtime；
2. `get_available_models` 读取模型目录，`get_available_thinking_levels` 读取推理强度目录；
3. `get_state` 读取当前模型与推理强度；
4. 对 `set_model` 请求，在模型目录中按唯一的 `provider + modelId` 精确匹配后调用 `set_model`；对 `set_thinking_level` 请求，在推理强度目录中精确匹配后调用 `set_thinking_level`；
5. 再次调用 `get_state`，将当前设置和同一次读取的两个目录回传；
6. 退出该空闲 RPC runtime。

设置切换不复用运行中的 Job runtime；服务端先以 `waiting_input` 拒绝非空闲请求，保证同一 Pi session 同时只有一条 RPC 控制路径。Bridge 不接受浏览器直连传入的模型、推理强度或目录，所有设置标识仅来自已经验证的服务端控制请求。

## 5. 代码落点

| 位置 | 落地内容 |
| --- | --- |
| `linear-lite-server/.../service/PiSettingsRequestService.java` | 内存设置请求、执行归属校验、超时释放与 `pi-settings-state` 发布。 |
| `linear-lite-server/.../controller/TaskAgentSessionController.java` | 创建读取/切换设置请求的任务侧接口。 |
| `linear-lite-server/.../controller/AgentController.java` | Bridge 领取、完成和失败设置请求。 |
| `linear-lite-server/.../dto/` | `PiSettingsState`、设置请求与 Bridge claim/complete DTO。 |
| `pi-bridge/src/index.mjs` | 增加 Pi RPC 的模型/推理强度读取与切换，并接入主领取循环。 |
| `src/services/api/agent.ts` | 设置请求方法与 `pi-settings-state` SSE 解码。 |
| `src/components/pi/PiConversationPanel.vue` | 维护仅面板生命周期内的 `PiSettingsState`、加载/应用状态与错误转发。 |
| `src/components/pi/PiTurnComposer.vue` | 截图同款配置胶囊、两级浮层与不可切换状态。 |

不修改 `agent_task_sessions`、`agent_task_jobs` 或 `scheam.sql`：模型与推理强度选择已由 Pi session 的设置条目持久化，新增数据库字段会形成第二条数据路径。

## 6. 删除与约束

- 不提供项目设置、全局默认、用户偏好、模型白名单、速度项、重置默认项或手工填写 provider/modelId。
- 不在前端、服务端或 Bridge 缓存上一次模型或推理强度以充当断连兜底。
- 不将任何设置切换伪装成一轮 Turn，不写任务评论，不创建 Job，也不影响任务状态。
- 不使用 Pi 的 `cycle_model` 或 `cycle_thinking_level`；菜单选择必须传递用户明确选择的唯一值。
- 不在运行中排队设置切换；用户必须等待当前轮结束。
