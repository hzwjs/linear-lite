# 甘特图视图实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 在项目工作区新增甘特图布局，基于 Frappe Gantt（Spike 验证）实现左右沿改期、中间平移且工期不变，并通过现有 `updateTask` 持久化 `plannedStartDate` / `dueDate`。

**方案概述：** 扩展 `ViewType` 为 `gantt`；新增 Vue 封装组件在 `mounted` 初始化 Frappe 实例并同步 `taskStore`；纯函数完成 `Task` 与甘特数据互转；拖拽结束调用 `updateTask` 乐观更新，失败回滚并重绘。

**技术栈：** Vue 3、Pinia、`frappe-gantt`（待定：Spike 后锁定版本）、现有 `taskStore` / `viewModeStore`、`vue-i18n`。

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

## 执行交接

计划已保存到 `docs/plans/2026-04-08-gantt-chart.md`。有两种执行方式：

1. **子代理驱动（当前会话）** — 按任务分派 subagent，任务间 review；需使用 `@superpowers:subagent-driven-development`。
2. **并行会话（独立执行）** — 新会话使用 `@superpowers:executing-plans`，在 worktree 中按批次执行并在检查点汇报。

选定方式后从 **任务 0（Spike）** 开始，**不得跳过 Spike**。
