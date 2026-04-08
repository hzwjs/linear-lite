# 甘特图视图实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 在项目工作区新增甘特图布局，基于 Frappe Gantt（Spike 验证）实现左右沿改期、中间平移且工期不变，并通过现有 `updateTask` 持久化 `plannedStartDate` / `dueDate`。

**方案概述：** 扩展 `ViewType` 为 `gantt`；新增 Vue 封装组件在 `mounted` 初始化 Frappe 实例并同步 `taskStore`；纯函数完成 `Task` 与甘特数据互转；拖拽结束调用 `updateTask` 乐观更新，失败回滚并重绘。

**技术栈：** Vue 3、Pinia、`frappe-gantt@^1.2.2`（Spike 结论：采用该库）、现有 `taskStore` / `viewModeStore`、`vue-i18n`。

---

### 任务 0：Spike — Frappe Gantt 交互与许可

**涉及文件：**
- 临时：可在外部最小 Vue 片段或 `src/views/_spike/`（Spike 结束后删除或不上线路由）

**步骤 1：** 安装 `frappe-gantt`，用硬编码 3 条任务验证：左沿、右沿、中间拖动是否分别满足设计文档 §1。

**步骤 2：** 阅读官方/源码中与 `on_date_change`（或等价 API）相关的签名，确认能否拿到新 `start`/`end`。

**步骤 3：** 记录结论到 Spike 笔记（可附在 PR 描述或本计划末尾「Spike 结论」）；**不通过**则创建 **任务 0B**：对 `ddmy/vue3-gantt` 重复同等验证。

**验证：** 书面结论「采用 Frappe」或「改用 vue3-gantt / 其他」。

**步骤 4：** 若 Spike 在仓库内留下代码，提交前删除临时路由或标记勿合并；正式实现从任务 1 开始。

---

### 任务 1：扩展视图类型与持久化

**涉及文件：**
- 修改：`src/utils/viewPreference.ts`（`ViewType`、`isViewType`、`normalizeViewConfig`）
- 修改：`src/store/viewModeStore.test.ts`（如有硬编码 `board`|`list`）

**步骤 1：** 将 `ViewType` 改为 `'board' | 'list' | 'gantt'`，默认值保持 `'list'`（或与产品当前默认一致）。

**步骤 2：** 运行 `pnpm test -- viewModeStore`（或项目等价命令）确认通过。

**步骤 3：** 提交：`git add src/utils/viewPreference.ts src/store/viewModeStore.test.ts && git commit -m "feat(view): 视图类型增加 gantt"`

---

### 任务 2：Board 顶栏增加甘特切换

**涉及文件：**
- 修改：`src/views/BoardView.vue`（与 Board / List 并列的切换控件）
- 修改：i18n 词条文件（如 `src/locales/*.json`）

**步骤 1：** 增加第三个按钮/分段控件，`@click` 调用 `viewModeStore.setView('gantt')`。

**步骤 2：** 运行应用手动点选三种布局，确认 `localStorage` 中 `viewConfig.layout` 可为 `gantt`。

**步骤 3：** 提交。

---

### 任务 3：任务 ↔ 甘特行映射（TDD）

**涉及文件：**
- 新增：`src/utils/ganttTaskMap.ts`
- 新增：`src/utils/ganttTaskMap.test.ts`

**步骤 1：** 编写失败测试，覆盖：双日期、仅 due、仅 plannedStart、无日期（应返回 `null` 或明确「跳过」标记）。

**步骤 2：** 运行测试，预期 FAIL。

**步骤 3：** 实现 `taskToGanttRow(task): GanttRow | null` 与 `ganttDatesToTaskPatch(start, end): { plannedStartDate, dueDate }`，**结束日含意**与 Frappe 一致（若库为 exclusive，在映射层处理）。

**步骤 4：** 测试 PASS 后提交。

---

### 任务 4：甘特容器组件

**涉及文件：**
- 新增：`src/components/GanttChart.vue`（或 `src/views/GanttPanel.vue`，与现有目录风格对齐）
- 修改：`src/views/BoardViewContent.vue`（`viewType === 'gantt'` 分支）
- 新增样式：scoped 或现有 CSS 变量，避免破坏 Board/List

**步骤 1：** 在 `onMounted` 中 `new Gantt(...)`（以 Spike 为准），`watch` `taskStore` 中当前项目顶层任务列表，过滤 `parentId` 后 `refresh` 数据。

**步骤 2：** 仅 **顶层任务** 进入图表（`!task.parentId`）。

**步骤 3：** 拖拽结束：调用 `updateTask(task.id, patch)`；失败时 `catch` 后重新 `parse` 自 store。

**步骤 4：** 手动验证三种拖拽与网络失败（可断网）回滚。

**步骤 5：** 提交。

---

### 任务 5：键盘无障碍与销毁

**涉及文件：**
- 修改：`src/components/GanttChart.vue`

**步骤 1：** `onUnmounted` 中销毁 Frappe 实例（若 API 支持 `clear`/`destroy`），避免路由切换泄漏。

**步骤 2：** 确认焦点不会卡在已卸载容器（必要时 `tabindex` 与主区域一致）。

**步骤 3：** 提交。

---

### 任务 6：全量验证

**步骤 1：** 运行 `pnpm run build`（或项目 CI 等价）无错误。

**步骤 2：** 运行全量单元测试 `pnpm test`（或等价）。

**步骤 3：** 提交（若仅有文档/锁文件变更可合并为一次 docs 提交）。

---

## Spike 结论（任务 0）

**锁定版本：** `frappe-gantt@1.2.2`（`package.json` 中为 `^1.2.2`，与当前 lockfile 解析一致）。

**许可：** npm 包 `license` 为 **MIT**（`node_modules/frappe-gantt/package.json`），与计划中的商业友好预期一致。

**设计 §1 三种拖拽（基于源码 `src/index.js`、`src/bar.js` 的行为推断；未在仓库内增加临时路由或页面做浏览器点拖复现）：**

| 交互 | 是否原生支持 | 依据 |
|------|--------------|------|
| 左沿 / 右沿 | 是 | `mousedown` 在 `.handle.left` / `.handle.right` 上分别置 `is_resizing_left` / `is_resizing_right`；`mousemove` 时左沿同时改 `x` 与 `width`，右沿只改 `width`（`bind_bar_events`），故可单独改 `start` 或 `end`，**工期（条宽）可变**。 |
| 条体中间平移、工期不变 | 是 | 在 `.bar-wrapper` 上（非左右 handle）`mousedown` 进入 `is_dragging`，`mousemove` 仅 `update_bar_position({ x })`，**不改变 `width`**；`compute_start_end_date` 由 `x` 与 `width` 推导起止，故时间轴上的跨度（列宽 × 列数）保持不变，对应 **平移且跨度不变**。 |
| 与「改进度」区分 | 是 | `.handle.progress` 圆点走 `bind_bar_progress`，结束触发 `progress_change`；**不**走改 `x`/`width` 的日期逻辑。 |

**假设：** 若启用 `readonly_dates: true` 或 `readonly: true`，中间平移与左右 resize 会被禁用；`fixed_duration: true` 会隐藏左右 resize 柄（`draw_resize_handles`），与「可改工期」冲突，正式集成时应保持默认 `fixed_duration: false`。

**`on_date_change`（等价 API）：** 官方 README 配置表未列出，但 `Gantt.trigger_event` 约定为 `options['on_' + event]`。`Bar.date_changed` 调用 `trigger_event('date_change', …)`，故选项名为 **`on_date_change`**。

**回调签名（与源码一致）：**

```text
on_date_change(task, newStartDate, endDateForDisplay)
```

- `task`：内部已更新 `task._start` / `task._end` 的同一对象引用。
- `newStartDate`：`Date`，由条左缘对齐的列推算。
- `endDateForDisplay`：`Date`，为 `date_utils.add(new_end_date, -1, 'second')`（`new_end_date` 来自条右缘；库内对「整日结束」常用次日 0 点表示，回调里减 1 秒给出便于展示的「含意上的结束时刻」）。

**注意：** `date_changed()` 在 `update_bar_position` 内每次都会调用，拖拽/resize 过程中 **`on_date_change` 可能连续触发多次**；`mouseup` 时若有位移会再次调用。持久化若需「仅提交一次」，应在集成层做防抖或仅在 `mouseup`/单次提交时写 API。

**推荐结论：** **采用 Frappe Gantt** 实现 §1；**不需要任务 0B**（`vue3-gantt` 备用可留作日后若集成受阻再评估）。正式实现从任务 1 起；本 Spike **未**向生产路由添加任何临时页面。

---

## 执行交接

计划已保存到 `docs/plans/2026-04-08-gantt-chart.md`。有两种执行方式：

1. **子代理驱动（当前会话）** — 按任务分派 subagent，任务间 review；需使用 `@superpowers:subagent-driven-development`。
2. **并行会话（独立执行）** — 新会话使用 `@superpowers:executing-plans`，在 worktree 中按批次执行并在检查点汇报。

选定方式后从 **任务 0（Spike）** 开始，**不得跳过 Spike**。
