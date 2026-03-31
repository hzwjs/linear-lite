# Task Row Status Quick Picker — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在任务列表行中，点击 ID 与标题之间的状态图标打开 Teleport 浮层，快速切换任务状态，行为与已确认设计一致。

**Architecture:** 新增 `TaskRowStatusPicker.vue`：图标按钮触发 + `Teleport` 到 `body` 的面板（顶栏只读占位 + 7 项状态列表），定位与监听参照 `CustomDatePicker.vue`。`TaskListView.vue` 用该组件替换原 `task-row-status` 的静态 `span`，选择后调用 `store.transitionTask`。

**Tech Stack:** Vue 3 Composition API、`vue-i18n`、`lucide-vue-next`、现有 `getStatusLabel`、`useTaskStore`。

**Design reference:** `docs/plans/2026-03-31-task-row-status-picker-design.md`

---

### Task 1: 新建 `TaskRowStatusPicker.vue`（触发器 + Teleport 面板骨架）

**Files:**

- Create: `src/components/TaskRowStatusPicker.vue`

**Step 1: 组件 API**

- `defineProps<{ taskId: string; status: Status }>`（`Status` 从 `../types/domain` 导入）。
- `defineEmits<{ change: [status: Status] }>` — 父组件负责 `transitionTask`，保持组件不直接依赖 store。

**Step 2: 触发器**

- `button.task-row-status-trigger`，`type="button"`，`@click.stop`，`@click` 切换 `isOpen`。
- 子节点：与 `TaskListView` 一致的 `statusIcons[status]` + `icon icon-14 status-icon` class（可从父传入或在本组件内复制 `statusIcons` Record）。
- `aria-expanded`、`aria-haspopup="listbox"`、`aria-controls` 指向面板 listbox id，`aria-label` 使用 i18n（若无 key，在 `src/locales` 增一条，如 `taskList.changeStatus`）。

**Step 3: Teleport 面板**

- `<Teleport to="body">`，内层 `div`：`position: fixed`、`z-index` 与 `CustomSelect` 列表同级或略高（如 1000+）、`ref="panelRef"`、`role="listbox"`、`tabindex="-1"`。
- `panelStyle`：`ref({ top: '0', left: '0' })`，绑定 `:style="panelStyle"`。
- 从 `CustomDatePicker.vue` 复制并简化 `updatePanelPosition()`：触发器 ref `triggerRef`，默认 `top = rect.bottom + 4`、`left = rect.left`；右半边或右溢出时 `left = rect.right - panelWidth`；下方溢出且上方够高则上展；最后 `Math.max(0, Math.min(...))` 夹紧视口。常量 `FALLBACK_PANEL_WIDTH` 可取 260–280。
- `open()`：`isOpen = true`，`nextTick` + `requestAnimationFrame` 调两次 `updatePanelPosition`（与 CustomDatePicker 一致，避免首帧宽高为 0）。
- `close()`：`isOpen = false`，`triggerRef?.focus()`。
- `watch(isOpen)`：为 true 时在 `window` 上 `addEventListener('resize', updatePanelPosition)` 与 `addEventListener('scroll', updatePanelPosition, true)`，为 false 时移除。`onUnmounted` 务必清理监听。

**Step 4: 点击外部关闭**

- `onMounted` 注册 `document` `click` 捕获/冒泡监听：若 `isOpen` 且点击目标不在 `triggerRef` 与 `panelRef` 内则 `close()`（与 `CustomSelect` 的 `handleClickOutside` 同理）。

**Step 5: 暂不放选项行**

- 可先放占位 div 验证定位；本 task 结束时能打开/关闭且面板在视口内。

**Step 6: 手动验证**

- 将组件临时挂到某一测试页或提前在 Task 2 接入后验证：列表滚动时面板不贴在内层被裁切。

**Step 7: Commit**

```bash
git add src/components/TaskRowStatusPicker.vue
git commit -m "feat(ui): TaskRowStatusPicker 骨架与 Teleport 定位"
```

---

### Task 2: 状态列表、键盘与顶栏（对齐 CustomSelect）

**Files:**

- Modify: `src/components/TaskRowStatusPicker.vue`

**Step 1: 选项数据**

- 在本组件内定义与 `TaskEditor.vue` 的 `statusOptions` 相同顺序与 shortcut：`backlog`…`duplicate`，图标 `CircleDashed`、`Circle`、`Loader2`、`Eye`、`CheckCircle`、`CircleX`、`Copy`（从 `lucide-vue-next`），`label: getStatusLabel(...)` + `useI18n()`。

**Step 2: 顶栏只读搜索**

- 与 `CustomSelect` 模板一致：`class="custom-select-search"` 区块内 `input` `readonly`、`tabindex="-1"`，placeholder 用 i18n（与 `TaskEditor` 里 `searchPlaceholder` 同源 key，如 `select` 相关文案）。**不要**渲染 `searchShortcutBadge`（设计选项 A）。

**Step 3: 选项行**

- `v-for` 渲染 `button` / `role="option"`：`option-icon`、`option-label`、当前项右侧 `Check`（`lucide-vue-next`），非当前项右侧 `kbd` 显示 shortcut。
- `@click`：`emit('change', optValue as Status)` 然后 `close()`；若所选等于 `props.status` 仅 `close()`。

**Step 4: 键盘**

- 面板 `@keydown`：复刻 `CustomSelect.vue` 的 `onListKeydown` — `Escape`、`ArrowDown`/`ArrowUp`、`Enter`、数字 `1`–`9` 匹配 `shortcut`。
- 触发器 `@keydown`：复刻 `onTriggerKeydown` 中 `Enter`/`Space`/`Escape`/箭头打开与导航逻辑，使按钮可键盘操作。
- `watch(isOpen)`：打开后 `setTimeout(() => panelRef.value?.focus(), 0)`。

**Step 5: 样式**

- 可复制 `CustomSelect.vue` 中 `.custom-select-list`、`.custom-select-search`、`.custom-select-option` 等 scoped 样式到本组件，或提取公共 class；保证与现有主题变量一致。

**Step 6: 手动验证**

- 数字键与方向键选择；`Escape` 关闭且焦点回按钮。

**Step 7: Commit**

```bash
git add src/components/TaskRowStatusPicker.vue
git commit -m "feat(ui): TaskRowStatusPicker 状态列表与键盘"
```

---

### Task 3: 接入 `TaskListView.vue`

**Files:**

- Modify: `src/components/TaskListView.vue`（`task-row-status` 所在模板块，约 355–358 行）

**Step 1: 替换模板**

- 删除：

```vue
<span class="task-row-status">
  <component :is="statusIcons[row.task.status]" class="icon icon-14 status-icon" />
</span>
```

- 改为：

```vue
<TaskRowStatusPicker
  :task-id="row.task.id"
  :status="row.task.status"
  @change="(s) => onStatusPicked(row.task, s)"
/>
```

**Step 2: 脚本**

- `import TaskRowStatusPicker from './TaskRowStatusPicker.vue'`。
- 若 `statusIcons` 仅用于此处则可删除；若仍被别处使用则保留（检查同文件 — 仅 status 行则用 picker 内图标，`statusIcons` 可从 TaskListView 移除）。
- 新增：

```ts
async function onStatusPicked(task: Task, next: Status) {
  if (next === task.status) return
  await store.transitionTask(task.id, next)
}
```

**Step 3: 样式**

- 将原 `.task-row-status` 下与触发器相关的样式迁移到 `TaskRowStatusPicker` 或保留包裹 class；确保行内对齐与改前一致（`display: inline-flex`、与 `task-row-key` 间距不变）。

**Step 4: 手动验证**

- 点击状态图标不打开详情；改状态后行与分组刷新正确；与「完成」按钮、`transitionTask` 行为一致。

**Step 5: Commit**

```bash
git add src/components/TaskListView.vue
git commit -m "feat(task-list): 行内状态图标打开快速选择器"
```

---

### Task 4: i18n 与回归检查（若有缺漏）

**Files:**

- Modify: `src/locales/*.json`（若新增 `taskList.changeStatus` 等）
- Modify: `src/components/TaskRowStatusPicker.vue`

**Step 1**

- 确认所有可见字符串走 i18n；与 `CustomSelect` 的 `select.searchAria` 等复用一致。

**Step 2**

- 运行 `pnpm test` / `npm test`（以仓库脚本为准），确保现有 `taskStore` 等测试仍通过。

**Step 3: Commit**（仅当有 locale 变更时）

```bash
git add src/locales
git commit -m "chore(i18n): 列表行状态选择器文案"
```

---

## Execution options

Plan complete and saved to `docs/plans/2026-03-31-task-row-status-picker.md`.

**1. Subagent-driven (this session)** — 每步一个子代理，步间 review。  
**2. Parallel session (separate)** — 新会话使用 executing-plans，在独立 worktree 中批量执行。

如需执行，指定 1 或 2。
