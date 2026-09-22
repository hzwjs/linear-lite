# Agent 初始化 Prompt 自动填充改造方案

## 1. 方案结论

在任务详情页首次打开 Agent 面板时，由前端基于当前任务编辑态组装初始化 Prompt，并直接填充到 Agent 输入框；不自动发送，用户可以在输入框中修改、补充后再提交。

Prompt 的唯一数据来源固定为任务详情当前编辑态：任务编号、任务标题和任务描述。任务描述沿用现有 `bodyToPlainText` 转换为纯文本，避免把 BlockNote JSON 直接传给 Agent。

本次只调整前端 Prompt 生成和输入框初始化，不新增后端接口、数据库字段或新的 Agent 执行链路。

## 2. 当前链路

```text
点击任务详情 Agent 图标
  -> TaskEditor.openAgentPanel()
  -> prepareLocalPi()
  -> PiConversationPanel
  -> PiTurnComposer
  -> TaskEditor.agentPrompt
  -> agentApi.submitTurn(prompt)
```

当前 `prepareLocalPi()` 完成后会将 `agentPrompt` 清空，输入框因此只显示占位文案“补充本轮指令…”。改造点位于 `TaskEditor` 的面板初始化阶段，不改变 `submitTurn` 请求结构。

## 3. 初始化 Prompt

Prompt 固定使用以下结构：

```text
请处理当前 Linear Lite 任务。

任务编号：{taskKey}
任务标题：{title}
任务描述：
{description}

请先理解任务目标，结合当前工作区完成实现；完成后说明实际改动和结果。
```

组装规则：

- `taskKey` 使用当前任务的唯一编号；
- `title` 使用任务详情当前编辑态标题；
- `description` 使用当前编辑态描述，并通过 `bodyToPlainText` 转为纯文本；
- 描述为空时仍保留“任务描述：”区块，不引入其他字段作为替代内容；
- 所有字段统一去除首尾空白，Prompt 末尾保留一个换行；
- 不把状态、优先级、负责人等非任务目标字段加入初始化 Prompt，保持指令短且聚焦。

## 4. 初始化时机与输入框行为

### 4.1 首次打开面板

`openAgentPanel()` 在设置 `agentPanelOpen` 后立即初始化 Prompt，再异步执行 `prepareLocalPi()`。这样即使 Bridge 准备耗时，用户也能立即看到可编辑的任务指令。

初始化只发生一次：以当前任务 ID 记录 `agentPromptInitializedTaskKey`。同一任务面板关闭后重新打开，不重新覆盖输入框内容，也不重复生成已经使用过的任务指令。

### 4.2 用户编辑优先

初始化 Prompt 写入 `agentPrompt` 后，输入框继续沿用现有双向绑定。用户的手工修改、删除和补充都不被状态轮询、Bridge 重连或 `prepareLocalPi()` 覆盖。

### 4.3 提交和后续轮次

- 初始化 Prompt 不自动发送；
- 用户提交后沿用现有 `agentApi.submitTurn(taskKey, executionId, prompt)`；
- 提交成功后继续清空输入框，后续轮次由用户输入新的指令；
- 任务切换时清空初始化标记和输入框，由新任务首次打开面板时重新生成；
- Agent 面板已打开期间，任务标题或描述变化不自动重写当前输入框，避免覆盖用户正在编辑的指令。

## 5. 实施范围

### 5.1 新增 Prompt 组装工具

新增 `src/utils/agentPrompt.ts`，提供唯一的 `buildInitialAgentPrompt` 函数：

- 输入固定为 `{ taskKey, title, description }`；
- 内部调用现有 `bodyToPlainText`；
- 只负责格式化，不读取 Store、不发请求、不修改 UI 状态；
- 不提供多字段兼容、空字段回退或多个 Prompt 模板。

### 5.2 调整 `src/components/TaskEditor.vue`

- 引入 `buildInitialAgentPrompt`；
- 增加当前任务的初始化标记；
- 在 `openAgentPanel()` 中按当前 `formTitle`、`formDescription` 和任务 ID 生成 Prompt；
- 保留 `prepareLocalPi()` 的 Bridge 绑定和 execution context 逻辑不变；
- 在任务切换/组件销毁时清理初始化标记；
- 删除 `prepareLocalPi()` 中无条件清空 `agentPrompt` 的行为，避免异步准备阶段覆盖已填充内容。

### 5.3 保持 `PiConversationPanel.vue` 和 `PiTurnComposer.vue` 接口不变

现有 `prompt` / `update:prompt` 双向绑定已经满足展示和编辑要求，不调整组件通信协议、发送快捷键、发送按钮状态和执行中禁用规则。

## 6. 固定数据流

```text
TaskEditor 当前编辑态
  ├─ task.id
  ├─ formTitle
  └─ formDescription
       ↓
bodyToPlainText(formDescription)
       ↓
buildInitialAgentPrompt()
       ↓
agentPrompt
       ↓
PiTurnComposer textarea
       ↓
agentApi.submitTurn(prompt)
```

该链路中只存在一条初始化 Prompt 来源，不从任务评论、活动记录、Agent 历史消息或后端新增字段拼接内容。

## 7. 完成定义

- 首次打开任务 Agent 面板时，输入框已显示包含任务编号、标题和描述的完整 Prompt；
- Prompt 展示为可读纯文本，不出现 BlockNote JSON；
- 用户可以在发送前继续编辑 Prompt；
- Prompt 不会因 Bridge 准备、状态轮询或重连被覆盖；
- Prompt 不会在初始化时自动发送；
- 提交成功后仍可继续进行下一轮 Agent 对话；
- 切换任务后，新任务首次打开面板使用新任务内容生成 Prompt。
