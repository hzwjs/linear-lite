# 列表行状态快速选择器 — 设计说明

**日期**: 2026-03-31  
**状态**: 已确认（用户选项 A：仅点击打开，无行级 `S` 快捷键）

---

## 1. 背景与目标

- **现状**: `TaskListView.vue` 中 `task-row-status` 仅展示当前状态图标（`statusIcons[task.status]`），点击会冒泡到整行，打开任务详情。
- **目标**: 点击 **ID 与标题之间的状态图标** 打开浮层，快速切换到任意 `Status`，视觉与交互对齐 Linear 风格参考图（顶栏占位 + 选项列表 + 当前项勾选 + 数字快捷键）。

---

## 2. 范围

### 2.1 包含

- 触发器：仅 `task-row-status` 区域；`stopPropagation`，不触发 `rowClick`。
- 浮层：`Teleport` 至 `body`，`position: fixed`，按触发器 `getBoundingClientRect()` 定位；视口边界翻转/夹紧，逻辑参照 `CustomDatePicker.vue` 的 `updatePanelPosition`。
- 打开期间监听 `window` 的 `resize` / `scroll`（`capture: true` 以捕获滚动容器内滚动），更新位置。
- 7 种状态：顺序、图标、`getStatusLabel`、快捷键 `1`–`7` 与 `TaskEditor.vue` / `IssueComposer.vue` 的 `statusOptions` 一致。
- 顶栏：与现有 `CustomSelect` 一致，**只读** 占位（如「Change status...」对应 i18n），**不**显示右上角 `S` 角标；**不**实现真实打字筛选。
- 键盘：打开后焦点在面板；`Escape` 关闭并聚焦回触发器；`↑`/`↓`、`Enter`、数字 `1`–`7` 与 `CustomSelect` 行为一致。
- 提交：选中后调用 `store.transitionTask(task.id, next)`；若与当前状态相同则仅关闭。
- 无障碍：触发器 `aria-expanded`、`aria-haspopup`、`aria-label`。

### 2.2 不包含（YAGNI）

- 列表行获得焦点时按 `S` 打开。
- 看板卡片、详情侧栏等同款交互（可后续单独需求）。
- 顶栏真实搜索过滤。

---

## 3. 技术要点

- **裁剪问题**: `.list-view-scroll` 使用 `overflow: auto`，行内绝对定位下拉会被裁切，因此面板必须 **Teleport + fixed**。
- **复用**: 新建专用组件（如 `TaskRowStatusPicker.vue`），避免直接套用 `CustomSelect`（其触发器为「标签+▼」且下拉相对定位）。选项行 markup/快捷键逻辑可与 `CustomSelect` 对齐以便日后抽取共享列表（非本需求强制）。
- **错误处理**: 与 `taskStore.transitionTask` 现有行为一致，不新增错误模型。

---

## 4. 验收

- 长列表内任意行点击状态图标，浮层完整可见、不被列表区域裁切。
- 滚动列表或视口后，浮层位置合理（不严重错位）；贴底/贴右时翻转或夹紧有效。
- 选状态后列表与 store 一致更新；选当前状态仅关闭。
- 不触发整行进入详情（除点击行内其他区域外）。

---

## 5. 参考文件

- `src/components/TaskListView.vue` — `task-row-status`、行点击
- `src/components/ui/CustomSelect.vue` — 列表 UI 与数字键逻辑参考
- `src/components/ui/CustomDatePicker.vue` — Teleport + `updatePanelPosition`
- `src/store/taskStore.ts` — `transitionTask`
