# 前端评审跟进（P1–P3）实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 落实 `docs/reviews/frontend-code-review-2026-04-11.md` 中的 P1–P3 项：乐观更新失败回滚、任务详情异步竞态防护、全局监听与定时器卸载清理、SSE 断线重连、主业务 chunk 瘦身。

**方案概述：** P1 在 `taskStore.updateTask` 内用补丁前快照失败回滚，并恢复父任务进度与收藏同步。P1 在 `TaskEditor` 各 loader 在每次 `await` 后校验当前 `props.task.id`（子任务树额外校验 `numericId`）或使用统一小工具避免重复逻辑。P2 用 `onUnmounted` 与 store 内定时器清理消除泄漏；SSE 采用可取消的退避重连并在登出时清空。P3 用 `vite build --analyze` 或 `rollup-plugin-visualizer` 定位权重后再做 `defineAsyncComponent` / 动态 `import()`。

**技术栈：** Vue 3、Pinia、Vitest（jsdom）、Vite 7、TypeScript。

**前置：** 在独立 git worktree 中执行（见 `@superpowers/using-git-worktrees`），避免与并行功能混杂。每批任务完成后按 `@superpowers/verification-before-completion` 运行验证命令再断言完成。

---

### 任务 1：`taskStore.updateTask` 失败回滚

**涉及文件：**
- 修改：`src/store/taskStore.ts`（`updateTask` 与必要时抽一私有函数 `rollbackTaskRow`）
- 测试：`src/store/taskStore.test.ts`
- 参考：`src/store/taskStore.ts:197-295`（`applyLocalTaskPatch` / `updateTask`）

**步骤 1：编写失败测试**

在 `src/store/taskStore.test.ts` 末尾增加用例：准备一条 `Task`，`taskApi.update` `mockRejectedValueOnce(new Error('network'))`，调用 `updateTask` 并 `await expect(...).rejects.toThrow('network')`，断言内存中任务字段与调用前一致（例如 `title`、`status`、`assigneeId`），且 `error.value` 含失败信息。

```ts
it('rolls back local optimistic patch when API update fails', async () => {
  const store = useTaskStore()
  const task: Task = {
    id: 'ENG-ROLL',
    numericId: 901,
    title: 'Before',
    status: 'todo',
    priority: 'medium',
    createdAt: 1,
    updatedAt: 1
  }
  store.tasks = [task]
  vi.mocked(taskApi.update).mockRejectedValueOnce(new Error('network'))

  await expect(
    store.updateTask('ENG-ROLL', { title: 'After' })
  ).rejects.toThrow('network')

  expect(store.tasks[0]?.title).toBe('Before')
  expect(store.error).toMatch(/network/i)
})
```

若项目里存在「乐观改 `parentId` 影响 `recomputeParentSubIssueProgress`」的用例需求，可再补一条：两个任务父子关系，`updateTask` 改子任务 `parentId` 失败，断言父任务 `completedSubIssueCount` / `subIssueCount` 与失败前一致。

**步骤 2：运行测试并确认失败**

运行：`npm run test -- src/store/taskStore.test.ts -t "rolls back local"`

预期：FAIL（标题仍为 `After` 或断言未通过）。

**步骤 3：编写最小实现**

在 `applyLocalTaskPatch` 调用之前：

- `const index = tasks.value.findIndex((t) => t.id === id)`，若 `index === -1` 则按现有逻辑短路。
- `const previous = { ...tasks.value[index]! }`；若 `Task` 上 `labels` 为数组引用且担心共享，可对 `labels` 做浅拷贝 `previous.labels ? [...previous.labels] : undefined`。
- 调用现有 `applyLocalTaskPatch`；在 `try` 内成功路径保持不变。
- 在 `catch` 内：`tasks.value[index] = previous`（或恢复为等价的完整行对象），然后对**回滚前乐观行**的 `parentId` 与 **previous.parentId** 调用 `recomputeParentSubIssueProgress`（若乐观更新改过 `parentId`，两个父链都需重算；与 `applyLocalTaskPatch` 内对称）。
- 若乐观路径曾 `useFavoriteStore().syncTask(next)`，回滚后对 `previous` 再 `syncTask(previous)`（仅当 `previous.favorited` 为真时与现有风格一致）。

**步骤 4：运行测试并确认通过**

运行：`npm run test -- src/store/taskStore.test.ts`

预期：PASS，且现有用例 `applies local task patch before API resolves` 仍通过（成功路径不变）。

**步骤 5：提交**

```bash
git add src/store/taskStore.ts src/store/taskStore.test.ts
git commit -m "fix: updateTask 失败时回滚乐观本地补丁"
```

---

### 任务 2：`TaskEditor` 异步 loader 竞态防护

**涉及文件：**
- 新增：`src/utils/taskLoadContext.ts`
- 新增：`src/utils/taskLoadContext.test.ts`
- 修改：`src/components/TaskEditor.vue`（`loadSubIssues`、`loadActivities`、`loadComments`、`loadAttachments` 及内部多处 `await` 之后）

**步骤 1：编写失败测试**

`src/utils/taskLoadContext.test.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { captureTaskLoadContext, isTaskLoadStale } from './taskLoadContext'

describe('taskLoadContext', () => {
  it('detects stale when task id changed', () => {
    const snap = captureTaskLoadContext({ id: 'A', numericId: 1 })
    expect(isTaskLoadStale(snap, { id: 'B', numericId: 2 })).toBe(true)
  })

  it('not stale when same task', () => {
    const snap = captureTaskLoadContext({ id: 'A', numericId: 1 })
    expect(isTaskLoadStale(snap, { id: 'A', numericId: 1 })).toBe(false)
  })

  it('null snapshot is stale', () => {
    expect(isTaskLoadStale(null, { id: 'A', numericId: 1 })).toBe(true)
  })
})
```

先不实现导出函数，或实现错误逻辑，保证第一步 RED。

**步骤 2：运行测试并确认失败**

运行：`npm run test -- src/utils/taskLoadContext.test.ts`

预期：FAIL（模块不存在或断言失败）。

**步骤 3：编写最小实现**

`src/utils/taskLoadContext.ts`：

```ts
export type TaskLoadSnapshot = { taskId: string; numericId: number | null | undefined }

export function captureTaskLoadContext(
  task: { id?: string; numericId?: number | null } | null | undefined
): TaskLoadSnapshot | null {
  if (task?.id == null) return null
  return { taskId: task.id, numericId: task.numericId }
}

export function isTaskLoadStale(
  snap: TaskLoadSnapshot | null,
  current: { id?: string; numericId?: number | null } | null | undefined
): boolean {
  if (snap == null) return true
  if (current?.id !== snap.taskId) return true
  if (snap.numericId != null && current?.numericId != null && current.numericId !== snap.numericId) {
    return true
  }
  return false
}
```

在 `TaskEditor.vue` 中：

- 每个 loader 开头 `const ctx = captureTaskLoadContext(props.task)`；若 `ctx == null` 则清空对应列表并 `return`（与现有 guard 一致）。
- **每一次** `await` 之后（含 `loadSubIssues` 里 `appendChildren` 递归中的 `await`），若 `isTaskLoadStale(ctx, props.task)` 为真则 `return`，不再写 ref。
- `loadAttachments` / `loadComments` / `loadActivities` 在 `finally` 里若提前 return 需注意 loading 状态：仅在「仍为本任务上下文」时清除 loading，或在 `return` 前分支处理（与当前 `silent` 逻辑兼容）。

**步骤 4：运行测试并确认通过**

运行：`npm run test`

预期：全部 PASS。

**步骤 5：提交**

```bash
git add src/utils/taskLoadContext.ts src/utils/taskLoadContext.test.ts src/components/TaskEditor.vue
git commit -m "fix: TaskEditor 异步加载按任务上下文丢弃过期响应"
```

---

### 任务 3：`NotificationCenter` 卸载时移除 document 监听

**涉及文件：**
- 修改：`src/components/NotificationCenter.vue`
- 新增：`src/components/NotificationCenter.test.ts`（与 `TaskLabelCombobox.test.ts` 相同模式：`createApp` + `createPinia` + 可选 `i18n`）

**步骤 1：编写失败测试**

`NotificationCenter.test.ts` 中 `vi.spyOn(document, 'addEventListener')` 与 `removeEventListener`，挂载组件后将 `open` 设为 `true`（通过暴露 ref 或点击 bell 按钮），再 `app.unmount()`，断言 `removeEventListener` 曾被调用且参数包含捕获阶段 `true`。

若不便操作内部 `open`，可给测试用 `wrapper.vm` 暴露——优先用与用户一致的方式：找到 `notification-bell` 按钮 `click` 两次打开再卸载前确保监听已挂。

**步骤 2：运行测试并确认失败**

运行：`npm run test -- src/components/NotificationCenter.test.ts`

预期：FAIL（卸载后 spy 显示未 remove）。

**步骤 3：编写最小实现**

在 `NotificationCenter.vue` 增加 `onUnmounted`（从 `vue` 导入）：若当前曾注册 `onClickOutside`，则 `document.removeEventListener('click', onClickOutside, true)`。可与 `watch(open)` 共用同一移除逻辑，避免重复代码（例如抽 `function detachClickOutside()`）。

**步骤 4：运行测试并确认通过**

运行：`npm run test -- src/components/NotificationCenter.test.ts`

预期：PASS。

**步骤 5：提交**

```bash
git add src/components/NotificationCenter.vue src/components/NotificationCenter.test.ts
git commit -m "fix: NotificationCenter 卸载时移除 document 点击监听"
```

---

### 任务 4：`LoginView` 验证码倒计时 interval 卸载清理

**涉及文件：**
- 修改：`src/views/LoginView.vue`
- 新增：`src/views/LoginView.test.ts`

**步骤 1：编写失败测试**

挂载 `LoginView`，mock `authStore.sendRegisterCode` resolve，触发发码与 `startResendCountdown`，`vi.spyOn(window, 'clearInterval')`，随后卸载组件，断言 `clearInterval` 被调用（或 `setInterval` 返回的 id 被清理）。可用 `vi.useFakeTimers()` 推进时间辅助。

**步骤 2：运行测试并确认失败**

运行：`npm run test -- src/views/LoginView.test.ts`

预期：FAIL。

**步骤 3：编写最小实现**

`import { onUnmounted } from 'vue'`，在 `onUnmounted` 中若 `resendTimer != null` 则 `window.clearInterval(resendTimer)` 并置 `resendTimer = null`。

**步骤 4：运行测试并确认通过**

运行：`npm run test -- src/views/LoginView.test.ts` 与 `npm run test`

预期：PASS。

**步骤 5：提交**

```bash
git add src/views/LoginView.vue src/views/LoginView.test.ts
git commit -m "fix: LoginView 卸载时清理验证码倒计时定时器"
```

---

### 任务 5：`notificationStore` SSE 断线重连（可取消退避）

**涉及文件：**
- 修改：`src/store/notificationStore.ts`
- 新增：`src/store/notificationStore.test.ts`

**步骤 1：编写失败测试**

`vi.stubGlobal('EventSource', class { static CONNECTING = 0; static OPEN = 1; static CLOSED = 2; url = ''; onerror: (() => void) | null = null; onopen: (() => void) | null = null; addEventListener = vi.fn(); close = vi.fn(); constructor() {} })`（按实际调用补全最小接口）。

- 创建 pinia，`useNotificationStore`，模拟已登录（mock `useAuthStore` 或设置 `localStorage` JWT 与 `isLoggedIn`）。
- 调用 `connectStream()`，取最后一次构造的实例，触发 `onerror`，断言一段时间后（`vi.advanceTimersByTime`）会再次构造 `EventSource`（重连）。
- 调用 `disconnectStream()` 或登出后，断言不再调度新的连接（`clearTimeout` 清理）。

**步骤 2：运行测试并确认失败**

运行：`npm run test -- src/store/notificationStore.test.ts`

预期：FAIL。

**步骤 3：编写最小实现**

在 `notificationStore.ts` 内：

- 模块级或闭包变量：`reconnectTimer`、`backoffMs`（初值如 `1000`，上限如 `30000`）。
- `disconnectStream`：除关闭 `eventSource` 外 `clearTimeout(reconnectTimer)`，`reconnectTimer = null`，`backoffMs` 复位。
- `es.addEventListener('open', () => { backoffMs = 1000 })`（或 `onopen` 若类型允许）。
- `onerror`：`es.close()`，置空引用，`reconnectTimer = setTimeout(() => { connectStream() }, backoffMs)`，`backoffMs = Math.min(backoffMs * 2, 30000)`。
- 保证 `connectStream` 入口若已无 token 则不重连。

**步骤 4：运行测试并确认通过**

运行：`npm run test -- src/store/notificationStore.test.ts` 与 `npm run test`

预期：PASS。

**步骤 5：提交**

```bash
git add src/store/notificationStore.ts src/store/notificationStore.test.ts
git commit -m "fix: 通知 SSE 断线后指数退避重连"
```

---

### 任务 6：主业务 chunk 体积优化（P3）

**涉及文件：**
- 修改：视分析结果而定，常见为 `src/components/BoardViewContent.vue` 或路由级入口中对 **Tiptap / Gantt / xlsx / photoswipe** 等重库的 `defineAsyncComponent(() => import(...))`
- 修改：可选 `package.json` devDependency `rollup-plugin-visualizer`
- 修改：可选 `vite.config.ts` 增加 `build.rollupOptions.output.manualChunks`（仅在有明确边界时）

**步骤 1：基线与可视化**

运行：`npm run build`

记录控制台中与 `BoardViewContent` 相关的 chunk 名与体积。

可选：安装 `rollup-plugin-visualizer`，在 `vite.config.ts` 的 `build` 中插件生成 `stats.html`，打开后确认最大依赖边。

**步骤 2：最小拆分决策**

优先对「非首屏必需」组件做动态导入（与现有 `TaskEditor` 测试里 stub  heavy 子组件的模式一致），避免把核心列表逻辑打成异步碎片。

**步骤 3：实现拆分**

例如将甘特图、富文本编辑器面板改为异步组件，保证路由进入看板时首包下降；注意加载态（`Suspense` 或占位 skeleton）与错误边界符合现有 UI。

**步骤 4：验证**

运行：`npm run build`（大 chunk 警告应减轻或消失）

运行：`npm run test`

运行：`npm run dev` 手动点进原功能路径确认无回归。

**步骤 5：提交**

```bash
git add vite.config.ts package.json package-lock.json src/...
git commit -m "perf: 拆分看板重依赖以降低首包体积"
```

---

## 全量验证（每个任务合并前）

```bash
npm run test
npm run build
```

预期：`227+` 测试通过（数量随新增测试略增），`vue-tsc` / `vite build` 无错误。

---

## 执行交接

计划已保存到 `docs/plans/2026-04-11-frontend-code-review-followups.md`。有两种执行方式：

**1. 子代理驱动（当前会话）** — 按任务分派 subagent，任务之间做 review，迭代更快。需配合 `@superpowers/subagent-driven-development`。

**2. 并行会话（独立执行）** — 在新 worktree 中新开对话，使用 `@superpowers/executing-plans` 按批次执行并在检查点汇报。

你希望采用哪一种？
