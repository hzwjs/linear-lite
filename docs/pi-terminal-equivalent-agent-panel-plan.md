# Pi Agent 面板 Session 同源输出方案

## 1. 方案结论

Agent 面板是 Pi session 的终端式只读视图。Pi 终端与 Linear Lite 必须读取同一个本地 Pi session，Linear Lite 不复制、不持久化、不重建会话历史。

面板内容由两层组成：Pi session 已落地历史作为稳定基线，当前 RPC 流式事件作为临时覆盖层。一轮结束后重新读取 Pi session，用新的稳定基线整体替换临时层。

## 2. 唯一事实源

```text
Pi session
├─ Pi 终端读取
└─ Pi RPC → Bridge → Linear Lite Agent 面板
```

- Pi session 是会话内容的唯一事实源；
- `executionId` 唯一定位 Linear Lite 当前执行上下文；
- Pi `sessionId` 唯一定位对应的本地 Pi session；
- Linear Lite 数据库只保存任务、执行关系和 `sessionId`，不保存消息、thinking、工具参数、工具结果或历史显示块；
- Job、任务评论、执行结果和 SSE 内存缓存不得参与重建 session 历史；
- Bridge 离线或 session 不可读时明确报错，不读取任何旧副本兜底。

## 3. Session 历史读取

Bridge 使用 Pi RPC `get_entries` 获取完整 session entries、当前 `leafId` 以及每个 entry 的 `id`、`parentId`。

Bridge 从 `leafId` 沿 `parentId` 还原当前活动分支，只转换 Pi 终端实际展示的内容：

- user 消息；
- assistant 正文；
- assistant thinking；
- tool call 的名称和参数；
- tool result 的最终输出和错误状态。

废弃分支、RPC 控制事件和终端不可见元数据不进入面板。历史块身份直接使用 Pi entry 和 tool call 的稳定身份，不使用 timestamp、Job ID 或前端生成 ID。

## 4. 历史读取链路

面板打开或切换 session 时：

1. 前端先建立 `executionId` 级 SSE 连接。
2. 前端提交一次 session 快照读取请求。
3. 服务端完成任务权限和执行归属校验，创建一次性读取指令。
4. Bridge 领取指令并连接同一个 Pi session。
5. Bridge 调用 `get_entries`，还原当前活动分支并生成完整 `SessionSnapshot`。
6. 服务端将快照通过 SSE 原样转发给请求面板。
7. 前端用该快照整体替换当前历史基线。
8. 服务端释放快照内容，只保留读取指令的完成状态。

一次性读取指令只承担 Bridge 调度，不存储任何 session 内容。

## 5. 内容生命周期

### 5.1 已落地历史

`SessionSnapshot` 只来自 Pi `get_entries`：

- 用户已经提交的指令；
- assistant 已写入 session 的正文与 thinking；
- 已写入 session 的工具调用与工具结果；
- Pi session 中真实存在的错误结果。

这些内容在面板关闭、重新打开或提交下一轮后重新从同一个 Pi session 读取。

### 5.2 临时输出

执行期间以下内容只形成 `RuntimeDisplayBlock`：

- `message_update` 的文字和 thinking 增量；
- `tool_execution_update` 的累计工具输出；
- 当前 assistant/tool 的 streaming 状态；
- spinner 和执行状态。

临时块只存在于 Bridge 当前进程、服务端 SSE 通道和前端内存中，不写数据库，也不追加 delta 历史。同一块只按 `blockId + revision` 原位覆盖。

连接状态、Bridge 错误和传输错误显示在面板状态区，不伪装成 session 消息。

## 6. 一轮结束后的收敛

收到 `agent_end` 后：

1. Bridge 停止发送当前轮增量。
2. Bridge 再次调用 `get_entries`。
3. Bridge 根据最新 `leafId` 生成完整 `SessionSnapshot`。
4. 服务端将快照透传给面板。
5. 前端整体替换历史基线。
6. 前端删除当前轮全部 `RuntimeDisplayBlock`。

执行中的视觉效果来自 RPC 临时层，执行完成后的最终内容全部来自 Pi session。

## 7. 显示模型

### 7.1 SessionSnapshot

```ts
interface SessionSnapshot {
  executionId: string
  piSessionId: string
  leafId: string | null
  blocks: SessionDisplayBlock[]
}
```

### 7.2 SessionDisplayBlock

```ts
interface SessionDisplayBlock {
  blockId: string
  entryId: string
  order: number
  kind: 'user' | 'assistant' | 'tool'
  phase: 'final' | 'error'
  content: AgentDisplayContent | null
  tool: AgentDisplayTool | null
  createdAt: string
}
```

稳定标识规则：

- user/assistant：`entryId + contentIndex`；
- tool：Pi `toolCallId`；
- tool call 与 tool result 严格按 `toolCallId` 合并为一个终端式工具块；
- `order` 由当前活动分支的 entry 顺序和 entry 内内容顺序确定。

### 7.3 RuntimeDisplayBlock

```ts
interface RuntimeDisplayBlock {
  executionId: string
  jobId: number
  blockId: string
  revision: number
  kind: 'assistant' | 'tool'
  phase: 'streaming' | 'error'
  content: AgentDisplayContent | null
  tool: AgentDisplayTool | null
}
```

Runtime 模型不得进入 session 历史存储。

## 8. RPC 实时归并规则

| Pi RPC 事件 | 临时层行为 |
|---|---|
| `message_start` | 创建当前 assistant 临时块 |
| `message_update.text_delta` | 原位更新 assistant 正文 |
| `message_update.thinking_delta` | 原位更新 thinking |
| `message_end` | 用完整消息覆盖当前临时块，等待 session 快照收敛 |
| `tool_execution_start` | 按 `toolCallId` 创建工具临时块 |
| `tool_execution_update` | 用累计 `partialResult` 覆盖工具输出 |
| `tool_execution_end` | 用最终 result 覆盖工具临时块，等待 session 快照收敛 |
| `agent_end` | 停止增量并触发 Pi session 重读 |

`partialResult` 是累计快照，Bridge 直接替换，不再次拼接。`message_end` 只用于改善快照重读前的短暂显示，不作为最终历史来源。

## 9. 接口调整

面板订阅 session 通道：

```text
GET /api/tasks/{taskKey}/local-pi/sessions/{executionId}/stream
```

请求读取真实 Pi session：

```text
POST /api/tasks/{taskKey}/local-pi/sessions/{executionId}/snapshot-requests
```

Bridge 领取读取指令：

```text
POST /api/bridge/session-snapshot-requests/claim
```

Bridge 回传一次性快照：

```text
POST /api/bridge/session-snapshot-requests/{requestId}/complete
```

SSE 事件固定为：

- `session-snapshot`：来自 Pi session 的完整活动分支；
- `runtime-display-block`：当前执行的临时块快照；
- `session-read-error`：Bridge 离线、session 不存在或读取失败。

删除当前以 `jobId` 为历史范围的显示块订阅接口。Job 只表示一轮执行，不再承担会话历史边界。

## 10. Bridge 职责

- 维护 `executionId → Pi session runtime` 的唯一映射；
- Pi 正在执行时复用当前 RPC runtime 读取 session；
- Pi 空闲时以同一 `sessionId` 启动只读 RPC runtime，读取完成后退出，不发送 prompt；
- 使用 `get_entries` 还原当前活动分支；
- 将 Pi entry 转换为终端可见的 `SessionDisplayBlock`；
- 将实时 RPC 更新转换为 `RuntimeDisplayBlock`；
- 不保存独立会话副本，不从 Job 或评论补齐历史。

## 11. 服务端职责

服务端只负责：

- 校验任务、用户、execution 和 Bridge 归属；
- 调度一次性 session 快照读取；
- 转发 `SessionSnapshot` 与 `RuntimeDisplayBlock`；
- 管理 SSE 连接和请求超时。

服务端不得：

- 持久化 session 内容；
- 根据 Job、评论、结果或旧事件重建历史；
- 将 SSE 内存快照作为 session 历史；
- 在 Bridge 离线时返回旧内容；
- 在多个字段之间回退读取显示数据。

## 12. 前端结构

```text
PiConversationPanel
  PiConversationViewport
    PiUserMessage
    PiAssistantMessage
    PiToolExecution
      PiToolCall
      PiToolResult
  PiTurnComposer
```

`PiConversationPanel` 维护两套明确隔离的状态：

```text
SessionSnapshot：Pi session 已落地历史
RuntimeOverlay：当前执行的临时输出
```

- 打开面板时先订阅 SSE，再请求 session 快照；
- 收到 `session-snapshot` 时整体替换历史，不逐条追加；
- 收到 `runtime-display-block` 时只更新临时层；
- `executionId` 不变时，Job 切换不得清空历史；
- `executionId` 变化时清空两层状态并读取新 session；
- 工具块保持命令、参数、真实输出和折叠交互；
- 长历史使用虚拟列表，完整内容仍来自同一个快照；
- Bridge 和传输错误显示在独立状态区。

## 13. 终端式内容效果

- assistant 正文按 Markdown 渲染，不使用聊天气泡；
- thinking 使用弱化、可折叠区域，不与最终正文混合；
- 工具块展示真实工具名、完整参数和 Pi 返回的真实结果；
- 命令、路径、代码、diff 和终端输出使用等宽字体；
- streaming 工具默认展开，最终工具按终端密度折叠；
- 失败工具保持展开并展示真实错误；
- 不显示“工具执行完成”“工具执行失败”等无语义流水；
- 页面高度只随真实 session 消息和工具调用数量增长。

## 14. 实施顺序

1. 将 Bridge 执行逻辑抽取为可复用的 session runtime，并接入 Pi `get_entries`。
2. 建立活动分支解析器和 `SessionSnapshot` 转换器。
3. 增加一次性 session 快照读取指令及服务端透传通道。
4. 将现有显示块 SSE 拆分为 `session-snapshot` 和 `runtime-display-block`。
5. 将前端状态拆分为稳定历史基线与临时覆盖层。
6. 在 `agent_end` 后执行 session 重读、基线替换和临时层清理。
7. 删除按 Job 缓存历史、旧显示块历史接口和所有历史重建路径。

## 15. 最终数据链路

```text
历史：
Pi get_entries
→ Bridge 活动分支解析
→ SessionSnapshot
→ 服务端透传
→ Agent 面板历史基线

实时：
Pi RPC update
→ RuntimeDisplayBlock
→ SSE
→ Agent 面板临时覆盖层

结束：
agent_end
→ 重新读取 Pi get_entries
→ 新 SessionSnapshot
→ 替换历史基线并清除临时层
```

该链路保证 Pi 终端和 Linear Lite 始终读取同一个 Pi session。Linear Lite 只提供终端式展示和交互，不维护第二份会话数据。
