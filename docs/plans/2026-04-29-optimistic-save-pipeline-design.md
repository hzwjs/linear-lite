# 任务写路径：乐观 UI + 防抖/节流 + 单 in-flight + pending 合并 + flush

本文约定前端对**同一任务（`taskKey` / `id`）**持久化时的统一管线，目标：**无感及时**、**不乱序**、**QPS 可控**、**关页/切走不丢**。

**可执行性**：§4.3–§4.5（成功/失败重算）、§6（lane 状态）、§9（API 与 Promise）、§10（drain 错误）为实现的硬约束；§7 为字段级合并表。

---

## 1. 目标与非目标

### 1.1 目标

- 输入后 UI **立即**反映（不阻塞主线程等待网络）。
- 稳定状态下，网络请求次数接近 **「语义变更次数」**，而非按键/指针事件次数。
- **同一任务**任意时刻 **最多一个** HTTP 写请求在途；**旧成功响应不得整行覆盖**其后产生的乐观变更（见 §4.3）。
- 用户失焦、关闭编辑面、切换路由或离开页面前，**未落盘的意图**会发出。
- 失败时行为可预期：**不得**因请求 A 失败而抹掉请求 A 进行期间已产生的意图 B（见 §4.5）。

### 1.2 非目标

- 本文不规定必须上 CRDT/OT（多人同时改同一字段的冲突合并）；若未来需要，在「版本号」一节上扩展。
- 不替代后端校验与权限；前端合并仅表达 **客户端意图**，最终以服务端返回为准。

---

## 2. 术语


| 术语 | 含义 |
|------|------|
| **资源键** | 唯一标识一次持久化作用域，通常为 `taskId`（taskKey）。列表里「多任务」各自一条管线。 |
| **意图（intent）** | 一次用户或代码产生的 `Partial<UpdatePayload>`，表示「希望服务器最终收敛到包含这些变更」。 |
| **ackBase** | **已确认的服务器基线**：上一次成功写请求返回并 reconcile 后的完整 `Task`（或列表加载时的初始行）。UI 真相由基线 + 未确认 overlay 导出。 |
| **inFlightPatch** | 当前 in-flight HTTP 请求 **已发出** 的 body（partial，与 `UpdateTaskRequest` 对齐）；无 in-flight 时为 `null`。 |
| **pendingPatch** | in-flight 期间（及 kick 前瞬间）**已乐观应用、尚未随下一次请求发出** 的合并 patch；无则为 `null`。 |
| **乐观应用** | 将 intent 合并进 **未确认 overlay**（见 §4），并同步刷新派生行。 |
| **flush** | 取消 debounce 计时，保证 intent 已进入协调器 pending 路径并 `pump`（仍遵守单 in-flight）。 |
| **drain** | 等待该 `taskId` 上 **in-flight 结束且 pending 已清空**（或超时 / 策略中止）。 |

---

## 3. 总览：分层职责

```
┌─────────────────────────────────────────────────────────────┐
│  展示层 / 编辑器：debounce·throttle·flush 触发源              │
│  （何时产生 intent、何时调用 flush）                          │
└───────────────────────────┬─────────────────────────────────┘
                            │ enqueueIntent(taskId, patch)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  写协调层（per-taskId lane）                                 │
│  · 维护 ackBase / inFlightPatch / pendingPatch               │
│  · 派生 store 行 = reconcile(ackBase, inFlightPatch, pending) │
│  · 单 in-flight；kick 链                                     │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTP PUT
                            ▼
                          后端
```

- **展示层**：决定「多快攒一批 intent」；不负责并发控制。
- **写协调层**：保证 **单飞 + 合并 + 正确 reconcile**；所有写入口进同一 lane。

---

## 4. 服务端基线 + 乐观 overlay（P0）

### 4.1 禁止的行为

- **禁止**：in-flight 请求 A 成功后，用 `serverTask_A` **整行**赋值 `tasks[id]`，而不考虑 A 进行期间用户已产生的 **pendingPatch**（及同字段再次修改）。  
- 现有 `taskStore.updateTask` 在成功后 `merged = { ...existing, ...updated }` 若 `existing` 未包含 B 的变更，仍会丢 B；协调器实现必须改为 §4.3 的公式。

### 4.2 派生展示行（单一公式）

对每条 lane，**列表/抽屉所读 Task** 定义为：

```text
displayedTask = fieldwiseReconcile(ackBase, inFlightPatch, pendingPatch)
```

**fieldwiseReconcile**（同一字段优先级从高到低）：

1. 若 `pendingPatch` 对该字段有「已定义意图」（含 clear 标志语义，见 §7），取 **pending**。
2. 否则若 `inFlightPatch` 对该字段有已定义意图，取 **inFlight** 所表达的乐观值（与发出去的 body 一致）。
3. 否则取 **ackBase**。

实现上可每 enqueue **直接写 store 行**为 `displayedTask`，但 **ackBase / inFlight / pending 仍为真源**，成功/失败时用 §4.3、§4.5 **重算** store 行，避免漂移。

### 4.3 成功：响应只更新基线，再叠加未确认 overlay

当 in-flight 请求（body = `inFlightPatch`，记 `sent = inFlightPatch`）**成功**，返回 `serverTask`：

1. `ackBase ← serverTask`（整行接受为新的服务器真相）。
2. `inFlightPatch ← null`（本请求已结束）。
3. **不重算 pendingPatch**（仍为用户尚未随下一请求发出的意图）。
4. **写回 store 行**：`row ← fieldwiseReconcile(ackBase, null, pendingPatch)`。

等价表述：**成功响应只更新 baseline；任何旧响应不得越过 `pendingPatch` 覆盖用户在其后输入的字段。**

若 `pendingPatch` 为空，则 `row = ackBase`，与「整行用服务端」一致。

### 4.4 Lane 状态机（与 §6 对齐）

每个 `taskId` 维护：

| 字段 | 说明 |
|------|------|
| `ackBase` | 上次成功 PUT 后的完整 `Task`；初始为列表/详情加载行。 |
| `inFlightPatch` | 当前请求 body；无请求时为 `null`。 |
| `pendingPatch` | 已乐观、待下一发送的合并 patch；无则为 `null`。 |
| `inFlightGen` | 单调递增；可选，用于丢弃乱入的异步回调（若未来有多源写）。 |

**enqueueIntent(id, patch)**：

1. `pendingPatch ← mergePatches(pendingPatch, patch)`（§7）。
2. `row ← fieldwiseReconcile(ackBase, inFlightPatch, pendingPatch)`（更新 store）。
3. 若 `inFlightPatch === null`（无在途请求），`kick(id)`。

**kick(id)**：

1. 若 `inFlightPatch != null` 或 `pendingPatch == null`，return。
2. `inFlightPatch ← pendingPatch`；`pendingPatch ← null`。
3. `row ← fieldwiseReconcile(ackBase, inFlightPatch, null)`（可选，与上式等价于 pending 空）。
4. 发起 `httpUpdate(id, inFlightPatch)`；在 **finally** 中：`inFlightPatch ← null`（成功路径在 §4.3 已清；失败见 §4.5），再 `kick(id)`。

**注意**：从 `pending` 挪到 `inFlight` 时，展示行仍应体现「in-flight + 新的 pending」；新 enqueue 在 in-flight 期间只增 `pendingPatch`。

### 4.5 失败：只撤销 in-flight，不抹杀 pending（P0）

当当前请求（body = `sent`，即失败前一刻的 `inFlightPatch`）**失败**：

1. **不修改** `ackBase`（服务器未接受本次变更）。
2. 丢弃本次传输意图：`inFlightPatch ← null`（在 finally 中统一清理）。
3. **写回 store 行**：`row ← fieldwiseReconcile(ackBase, null, pendingPatch)`。

因此：用户在 A in-flight 期间产生的 **pendingPatch** 在 A 失败后仍完整保留；**禁止**「回滚到 A 发起前快照」若该快照会丢掉 pending 中的字段。

若需 **错误提示**：仅针对「本轮发送的 `sent`」关联的 UI（如 toast）；不要求把 pending 一并标为失败。

### 4.6 与「快照回滚」的关系

- **不再采用**「单次 `updateTask` 入口快照整行、失败则整行恢复」作为 **lane 内并发** 的唯一机制；它与 B-in-pending 冲突。
- 若调用方仍要 `try/catch`：**reject 只表示「某次可识别的 in-flight 失败」**；catch 后 store 行仍由 §4.5 公式维护，不应再手动覆盖整行。

### 4.7 派生字段 / 字段组 reconcile 规则（P1）

`fieldwiseReconcile` 不能把所有字段都当作“独立可覆盖字段”。以下字段需按**字段组**处理，避免 `status` 与 `completedAt`、父任务计数等出现不一致。

#### 4.7.1 任务完成态字段组（`status`、`progressPercent`、`completedAt`）

- 组内本地规约函数：`deriveCompletionGroup(patch, base)`。
- 规则：
  1. 若 `pendingPatch`（否则 `inFlightPatch`）包含 `status` 或 `progressPercent`，则该组优先采用该 patch，并执行本地规约：
     - 规约结果为“未完成状态”时，`completedAt` 本地强制为 `undefined/null`（不继承 `ackBase.completedAt`）。
     - 规约结果为“完成状态”时，`completedAt` 不做本地伪造时间，保持“未知待服务端确认”；最终以 `ackBase`（服务端响应）为准。
  2. 当 overlay 中该组无意图时，整组回落到 `ackBase`。
- 目的：避免出现 `status=todo` 且 `completedAt` 仍保留旧值的矛盾。

#### 4.7.2 父子统计字段组（`subIssueCount`、`completedSubIssueCount`）

- 该组定义为**服务端权威只读组**：`fieldwiseReconcile` 不从 `pendingPatch/inFlightPatch` 覆盖该组。
- 任务行上此组值始终取 `ackBase`，并依赖现有本地 `recomputeParentSubIssueProgress` 做临时显示修正；任一成功响应后以服务端返回再收敛。
- 目的：避免 overlay 对统计字段做“半覆盖”，导致与实际子任务集合不一致。

#### 4.7.3 父关系字段组（`parentId`、`clearParent`）

- 组内优先级沿用 §7：`clearParent` 优先；本地展示层可将其解释为 `parentId = null`。
- 与 4.7.2 配合：`parentId` 的 overlay 可立即生效，但父任务统计仍以服务端权威收敛。

---

## 5. 防抖与节流（展示层参数）

### 5.1 何时防抖（debounce）

适用于 **连续高频**、且 **中间状态无业务价值** 的字段：

- 标题、描述/富文本、备注等。

建议：

- `debounceMs`：**500–800ms**（移动端可略大）。
- **trailing：必须开启**（停手后再打一拍，避免永远不发）。
- **leading：可选**（首字立即发一次会增 QPS，一般关）。
- **maxWait**：**2000–3000ms**（用户一直输入时仍周期性落盘，避免长会话零请求）。

### 5.2 何时节流（throttle）

适用于 **高频但需定期同步** 或 **指针移动**：

- 进度百分比拖动、滑块类字段。

建议：

- `throttleMs`：**100–200ms**；或 **pointerup 再发一次最终值**（中间仅本地）。

### 5.3 何时立即 enqueue（不防抖）

- 状态流转、优先级、负责人、日期选择器 **选定**、标签勾选完成等 **离散提交**。
- 仍应进入 **同一 lane**。

### 5.4 展示层不得做的事

- 不得绕过协调器直接 `taskApi.update`。

---

## 6. 单 in-flight + pending 合并（与 §4 统一）

§4.4 已给出状态与 `kick`/`enqueue` 关系。补充：

- **finally 链式 `kick`**：当前请求结束后，若 `pendingPatch != null`，下一发自动开始，直至队列空。
- **generation**：仅在存在 **旁路** 修改同一 `taskId`、无法单飞时用于忽略过期响应；在「所有写经 lane」前提下可省略。

---

## 7. mergePatches 与 fieldwise 优先级（P1/P2）

### 7.1 总原则

- **patch 合并**（写入 `pendingPatch`）：同一字段 **last-write-wins**（以最后一次 `enqueueIntent` 为准）。
- **与展示 reconcile 一致**：pending 覆盖 inFlight 覆盖 ackBase（§4.2）。

### 7.2 字段组合与 clear 标志（含 clearParent）

与后端 `UpdateTaskRequest`（`linear-lite-server/.../UpdateTaskRequest.java`）及前端 `UpdateTaskRequest`（`src/services/api/types.ts`）对齐。**实现前**前端类型应补 `clearParent?: boolean`，与后端一致。

| 域 | 合并（pending 内多次 intent） | 与 `ackBase` reconcile | 请求体 `toRequestBody` 注意 |
|----|------------------------------|------------------------|---------------------------|
| `title` / `description` / `status` / `priority` / `progressPercent` | 后者覆盖前者 | pending 有则取 pending | 仅包含有意图的键 |
| `assigneeId` + `clearAssignee` | 同一 intent 内：`clearAssignee === true` 优先于 `assigneeId`；跨 intent：后者整体覆盖 | pending 有任一子键则整组按 pending | 勿同时发矛盾体；以后写为准 |
| `dueDate` + `clearDueDate` | `clearDueDate === true` 优先于同 intent 的 `dueDate`；跨 intent 后者覆盖 | 同上 | 同上 |
| `plannedStartDate` + `clearPlannedStart` | 同上 | 同上 | 同上 |
| **`parentId` + `clearParent`** | **`clearParent === true` 表示解绑（parent null）**；与同 intent 的 `parentId` 冲突时 **clearParent 优先**；跨 intent 后者覆盖 | pending 有则按 pending | **禁止**同一 body 同时发语义冲突的 `parentId` 与 `clearParent`；以后写为准 |
| `labels` | 后端为 **整包替换**；合并为 **最后一次带 `labels` 的 intent** | pending 有 `labels` 则用 pending 整包，否则 ack | 仅在有脏标签时带 `labels` |

### 7.3 toRequestBody

- 发送 **`inFlightPatch` 规约后的 partial body**（非整行 PUT 未声明字段不应被「清空」）。
- `parentId: null` 与 `clearParent: true` 在后端二选一语义下，**优先显式 `clearParent`**（与 Java 注释一致）。

---

## 8. flush / drain 与错误传播（P1）

### 8.1 触发源


| 场景 | 行为 |
|------|------|
| 输入框 `blur` | `flushTask(id)` |
| 关闭任务抽屉 / 销毁编辑器 | `flushTask(id)` + `await drainTask(id, opts)`（见下） |
| 路由 `beforeRouteLeave` | 同上 |
| `beforeunload` / `visibilitychange` | 尽力 `flush`；受浏览器限制见原文档注 |
| 切换当前编辑任务 | 对旧 `id` `flush` + `drain`（可选）再切 |

### 8.2 `flushTask(id)`

- **职责边界（写死）**：`flushTask` **不负责**操作组件私有 debounce timer。
- `flushTask(id)` 仅做两件事：`pump(id)` + 返回「lane 已被触发」的结果；不代表服务器成功。
- 展示层必须先调用 `flushEditorDraft(id)`（组件侧），把最后一次草稿 `enqueueIntent` 到 lane，再调用 `flushTask(id)`。
- 若某模块未实现 `flushEditorDraft`，`flushTask` 不保证捕获该模块尚未出队的草稿。

### 8.3 `drainTask(id, opts?)`

- **语义**：直到 `inFlightPatch === null` 且 `pendingPatch === null`（队列与在途皆空），或 `opts.timeoutMs` 到期。
- **失败边界（写死）**：采用“**最终未保存 intent**”口径，而非“期间任一失败”口径。  
  - 若 A 失败后 B 最终成功且队列清空，`drainTask` 结果应视为 **ok**（不返回 `save_failed`）。  
  - 仅当 drain 结束时仍有未保存意图（例如最后一次发送失败并停止重试）或超时，才返回失败态。
- **Promise 行为（必须二选一并写死，推荐方案 A）**：  
  - **方案 A（推荐）**：`drainTask` **fulfill** `DrainResult`：`{ ok: true, task: Task } | { ok: false, reason: 'timeout' | 'save_failed', task: Task, lastError?: Error }`。  
    - 关闭抽屉 / 路由：若 `ok === false`，由 UI 决定是否拦截（例如 `reason === 'timeout'` 提示「可能未保存」仍允许离开；`save_failed` 弹窗确认）。  
  - **方案 B**：最后一次 in-flight **reject** 则 **reject(drainPromise)**，`timeout` 也 **reject**；`catch` 中从 store 读当前 `task`。  
- **禁止**：`catch` 吞错后仍 `resolve`，导致调用方误以为已持久化。

### 8.4 与路由守卫

- **默认产品**：`save_failed` 时 **提示 + 可选留在当前页**；`timeout` **不强制阻塞**（避免卡死）。  
- 文档化到 PRD/本页即可；实现时 `drainTask` 的 `ok/reason` 需单一来源。

---

## 9. `updateTask` 与调用方契约（P1）

### 9.1 现状

- `TaskEditor` 等：`await store.updateTask(id, body)` 依赖 **成功后的 `Task`** 更新 `formStatus`、`formProgressPercent`、`formLabels` 等（见 `TaskEditor.vue` 保存路径）。

### 9.2 推荐对外 API

| API | 返回 / 行为 |
|-----|-------------|
| `enqueueTaskUpdate(id, patch)` | `void` 或 `{ intentId }`；只做 §4.4 enqueue + pump；**不** await 网络。 |
| `updateTask(id, patch, options?)` | **默认**与现有一致：`Promise<Task>`，**resolve 时机**为：包含本 `patch` 的那一轮（或合并后的 body）**已成功**且已执行 §4.3 reconcile 后的 **`displayedTask`（应与 store 行一致）**；若该轮 **reject**，则 `updateTask` **reject**（与现 `try/catch` 兼容）。 |
| `flushTask(id)` | §8.2 |
| `drainTask(id, opts?)` | §8.3 |

### 9.3 `updateTask` 与合并写

- 若本 `patch` 被合并进更大的 `inFlightPatch` 与其他 intent 同发：**仍只 resolve 一次**，且返回的 `Task` 为 **该次成功后的 reconcile 行**（含他人同批合并字段的服务器值 + 仍挂在 `pendingPatch` 上的更新）。
- 若本 `patch` 仅在 `pendingPatch` 中、尚未发出，`updateTask` 的 Promise 应 **挂起** 直至包含它的请求成功或失败 reject。

### 9.4 迁移

- 甘特 / 列表等 **fire-and-forget** 可逐步改为 `enqueueTaskUpdate`；需错误 toast 的保留 `updateTask` 或监听 lane 错误流。

---

## 10. 观测与上限（保护后端）

- 客户端对单任务 **maxWait + throttle** 已限制峰值；可再加全局并发写上限（多任务场景）。
- 日志：`save_scheduled` / `save_sent` / `save_reconciled` / `save_error`（含 `taskId`、body 键、`intentId`）。

---

## 11. 小结清单（实现验收）

- [ ] 所有写任务经 **单一 per-task lane**。
- [ ] 每 `taskId` **同时最多一个** HTTP in-flight。
- [ ] 成功：**`ackBase ← serverTask`**，**`row ← fieldwiseReconcile(ackBase, null, pendingPatch)`**；禁止裸 `row = serverTask` 当 `pendingPatch` 非空。
- [ ] 失败：**`ackBase` 不变**，**`row ← fieldwiseReconcile(ackBase, null, pendingPatch)`**；禁止整行回滚到 in-flight 前快照以致丢 pending。
- [ ] `fieldwiseReconcile` 实现字段组规则：完成态组、父关系组、父子统计组（§4.7）。
- [ ] `updateTask` 的 `Promise<Task>` settle 规则与 §9 一致；`drainTask` 错误与 §8.3 一致。
- [ ] §7 合并表（含 **`clearParent`**）与 `toRequestBody` 一致；前端 `UpdateTaskRequest` 补 `clearParent`。
- [ ] 展示层实现 `flushEditorDraft(id)`，并在离场路径执行 `flushEditorDraft -> flushTask -> drainTask`。
- [ ] 单测：§12。

---

## 12. 单测矩阵（实现前必写）

1. **A in-flight，B 仅 pending**：A 成功返回 `serverTask`；断言 store 行在 B 涉及字段上 **等于 B**，非 `serverTask` 裸值（若 B 覆盖 A 同字段）。  
2. **A in-flight，B 仅 pending**：A **失败**；断言 B 字段仍保留；`ackBase` 与 A 前一致。  
3. **A 成功后 pending 空**：行等于 `serverTask`。  
4. **同字段 clear 与值**：`clearDueDate` 与 `dueDate` 交叉多 intent，合并与 body 与 reconcile 一致。  
5. **`parentId` / `clearParent`**：多 intent 交叉，以后写为准；请求体无矛盾。  
6. **`updateTask`**：连续两次 `await updateTask`，第二次 resolve 的 `Task` 包含第二次意图；mock 合并为一次 HTTP 时 intent 链仍正确。  
7. **`drainTask`**：`save_failed` / `timeout` 时返回或 reject 行为符合 §8.3 选定方案。
8. **派生字段组**：`A: status=done` 返回 `completedAt`，`B pending: status=todo` 时，reconcile 后必须 `status=todo` 且 `completedAt` 清空。  
9. **drain 边界**：`A fail -> B success -> queue empty`，`drainTask` 返回 `ok:true`；`最后一次失败且无后续成功` 才是 `save_failed`。  
10. **flush 所有权**：未先执行 `flushEditorDraft` 时 lane 不应神奇提交组件私有草稿；执行后应可被 `drainTask` 观察到。

---

## 13. 参考伪代码（骨架，与 §4 一致）

```ts
type Patch = Record<string, unknown>

function fieldwiseReconcile(ack: Task, inflight: Patch | null, pending: Patch | null): Task {
  // 对每个业务字段按 §4.2：pending > inflight > ack
}

const lanes = new Map<string, {
  ackBase: Task
  inFlightPatch: Patch | null
  pendingPatch: Patch | null
}>()

export function enqueueIntent(id: string, patch: Patch) {
  const L = lane(id)
  L.pendingPatch = mergePatches(L.pendingPatch, patch)
  syncRowFromLane(id)
  if (L.inFlightPatch == null) pump(id)
}

function syncRowFromLane(id: string) {
  const L = lane(id)
  tasks[rowIndex] = fieldwiseReconcile(L.ackBase, L.inFlightPatch, L.pendingPatch)
}

async function pump(id: string) {
  const L = lane(id)
  if (L.inFlightPatch != null || L.pendingPatch == null) return
  L.inFlightPatch = L.pendingPatch
  L.pendingPatch = null
  syncRowFromLane(id)
  try {
    const serverTask = await taskApi.update(id, L.inFlightPatch)
    L.ackBase = serverTask
    L.inFlightPatch = null
    syncRowFromLane(id)
  } catch (e) {
    L.inFlightPatch = null
    syncRowFromLane(id)
    throw e
  } finally {
    pump(id) // 若 pending 非空继续发
  }
}
```

**说明**：真实实现需在 `finally` 中统一清空 `inFlightPatch`、处理 `updateTask` 的 per-intent Promise 表；上式省略 `intentId` 与 `drain`。

---

*文档版本：2026-04-29（修订：基线+overlay、字段组 reconcile、Promise/drain 边界、flush 所有权、clearParent、单测矩阵）。*
