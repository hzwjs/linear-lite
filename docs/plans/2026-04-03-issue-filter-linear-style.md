# 列表筛选（Linear 风 Add Filter）实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 将看板顶栏筛选改为 Linear 风格的 Add Filter（主菜单 + 子面板），在保留状态/优先级/负责人的前提下增加 **按标签多选 OR 筛选**；子菜单不展示数量；筛选仍在前端对已加载任务生效；项目级偏好持久化包含标签筛选。

**方案概述：** 在 `taskStore` 增加 `filterLabelIds` 并在 `filteredTasks` 中实现 OR 逻辑；扩展 `projectBoardPreferences` 的 `TaskFilterSnapshot`；新增主壳组件挂载于 `BoardView` 替换现有筛选 popover 内联表单项；Labels 子面板调用 `projectApi.listLabels` 并支持搜索；`BoardViewContent` 与 `activeFilterCount` 将「有标签筛选」视为与其它维度并列，且 **计数 +1**。

**技术栈：** Vue 3、TypeScript、Pinia、Vitest；现有 `CustomSelect`、`projectApi.listLabels`。

**设计依据：** `docs/plans/2026-04-03-issue-filter-linear-style-design.md`

---

### 任务 1：`taskStore` 标签筛选逻辑与单测

**涉及文件：**

- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/store/taskStore.ts`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/store/taskStore.test.ts`

**步骤 1：编写失败测试**

在 `taskStore.test.ts` 增加用例（不修改 store 前应先红）：构造 `tasks` 含不同 `labels`，设置 `filterLabelIds` 为 `[1]` 与 `[1,2]`，断言 `filteredTasks` 分别为「至少带标签 1」与「带 1 或 2」；再测与 `filterStatus` 组合。

```ts
// 示例断言思路（需按实际 store API 赋值）
store.tasks = [
  { id: 'A', labels: [{ id: 1, name: 'Bug' }], ... },
  { id: 'B', labels: [{ id: 2, name: 'Feat' }], ... },
  { id: 'C', labels: [], ... }
]
store.filterLabelIds = [1]
expect(store.filteredTasks.map((t) => t.id)).toEqual(['A'])
store.filterLabelIds = [1, 2]
expect(new Set(store.filteredTasks.map((t) => t.id))).toEqual(new Set(['A', 'B']))
```

**步骤 2：运行测试并确认失败**

运行：`npm test -- src/store/taskStore.test.ts`  
预期：失败（无 `filterLabelIds` 或过滤未实现）。

**步骤 3：最小实现**

- `taskStore` 增加 `filterLabelIds = ref<number[]>([])`（或 `ref<Set<number>>` 对外暴露为数组）。
- 增加 `toggleFilterLabelId(id: number)` / `removeFilterLabelId` 等最小 API（按 UI 需要二选一，避免过度设计）。
- `filteredTasks` 中：若 `filterLabelIds.length > 0`，保留任务满足 `task.labels?.some((l) => filterLabelIds.includes(l.id))`。
- `clearIssueFilters` 中：`filterLabelIds.value = []`。
- `return` 中导出 `filterLabelIds` 与 toggle/remove 方法。

**步骤 4：运行测试并确认通过**

运行：`npm test -- src/store/taskStore.test.ts`  
预期：PASS。

**步骤 5：提交**

```bash
git add src/store/taskStore.ts src/store/taskStore.test.ts
git commit -m "feat(taskStore): 支持按标签 OR 筛选"
```

---

### 任务 2：删除项目标签时同步筛选集

**涉及文件：**

- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/store/taskStore.ts`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/store/taskStore.test.ts`

**步骤 1：测试**

`stripProjectLabelFromTasks(projectId, labelId)` 调用后，若 `filterLabelIds` 含该 `labelId`，应移除。

**步骤 2：实现**

在 `stripProjectLabelFromTasks` 末尾或合适处：`filterLabelIds.value = filterLabelIds.value.filter((id) => id !== labelId)`（若仅当 `projectId === activeProject` 时需处理，按当前 store 是否依赖 `projectStore` 决定；**最简单**为始终从数组去掉该 id）。

**步骤 3：验证与提交**

运行：`npm test -- src/store/taskStore.test.ts`  
提交：`fix(taskStore): 删除标签定义时移除对应筛选 id`

---

### 任务 3：项目看板偏好读写 `filterLabelIds`

**涉及文件：**

- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/utils/projectBoardPreferences.ts`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/views/BoardView.vue`（`buildBoardSnapshot` / `applyBoardSnapshot`）
- 新增或修改测试：若已有 `projectBoardPreferences` 测试则扩展，否则在 `src/utils/projectBoardPreferences.test.ts` 补充（如无则新建最小单测）

**步骤 1：`TaskFilterSnapshot`**

增加 `filterLabelIds: number[]`，`parseFilters` 内对数组做校验：仅保留 `typeof id === 'number' && Number.isFinite(id)` 的项，默认 `[]`。

**步骤 2：`BoardView.vue`**

- `buildBoardSnapshot` 写入 `filterLabelIds: [...store.filterLabelIds]`（或等价拷贝）。
- `applyBoardSnapshot` 还原到 store。

**步骤 3：单测**

序列化再解析后 `filterLabelIds` 一致；非法 JSON 字段回退 `[]`。

**步骤 4：提交**

`feat: 持久化标签筛选到项目看板偏好`

---

### 任务 4：`BoardViewContent` 空态与分组「筛选激活」

**涉及文件：**

- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/views/BoardViewContent.vue`

**步骤：**

- `taskFiltersActive` 与 `emptyFilterHint` 中的 `hasIssueFilters` 增加 `store.filterLabelIds.length > 0`。
- 运行：`npm test`（或至少与 `taskView` / Board 相关测试若有）。

**提交：** `fix: 标签筛选参与空态与分组提示`

---

### 任务 5：新增 `AddIssueFilterMenu` 组件（主菜单 + 子面板）

**涉及文件：**

- 新增：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/components/issue-filters/AddIssueFilterMenu.vue`（路径可按项目惯例微调，但保持单一主组件 + 可选子组件）
- 新增（可选）：`src/components/issue-filters/IssueFilterLabelSubmenu.vue`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/views/BoardView.vue`
- 修改：`/Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/src/i18n/messages/zh-CN.ts`、`en.ts`

**步骤 1：样式与结构**

- 主面板：顶部 input「Add Filter…」placeholder；下方四个维度行，带图标与 chevron；主搜索仅过滤 **维度标题**（i18n 文案匹配）。
- 子面板：在桌面端 **mouseenter** 打开、**mouseleave** 延迟关闭（避免闪烁，参考常见菜单 100–200ms）；同时 **click** 可固定打开；内部：
  - Status / Priority / Assignee：嵌入现有 `CustomSelect`（与当前 `BoardView` 相同的 options 计算可经 props 传入或由父级 `BoardView` 传入 options）。
  - Labels：`onMounted` 或首次展开时 `projectApi.listLabels(projectId)`，同一 `projectId` 内存缓存；搜索框 `debounce` 调 `listLabels(projectId, query)`；列表项 checkbox，切换 `store.toggleFilterLabelId`。
- **不显示** 任何数量列。

**步骤 2：接入 `BoardView`**

- 将原 `.popover-filter` 内三个 section + 清除按钮 **替换** 为新组件（清除按钮仍调用 `clearIssueFiltersPanel`）。
- `activeFilterCount`：`filterLabelIds.length > 0` 时 `n++`（**只加 1**）。
- `filterButtonAria` 已用 `n`，无需改文案键。

**步骤 3：顶栏 chips**

- 在 command bar 漏斗旁或现有区域展示：状态/优先级/负责人若有则各一 chip（与 Linear 一致可后续微调）；**每个选中标签** 一颗 chip，点击关闭调用 `removeFilterLabelId`。
- 若当前顶栏无 chip 仅按钮，需增加一条横向 chip 区（注意与现有 UI 不冲突）。

**步骤 4：键盘**

- 保持 `@keydown.capture` 与 `CustomSelect` 打开时 Escape 不关闭外层（沿用现有 `onFilterPanelKeydownCapture` 逻辑）。

**步骤 5：手动与自动验证**

- 运行：`npm run build`
- 可选：`npm test`
- 浏览器：切换项目后标签筛选清空；清除按钮清空标签；持久化刷新后恢复。

**提交：** `feat(board): Linear 风 Add Filter 与标签筛选 UI`

---

### 任务 6：收尾与回归

**步骤：**

- 全局检索 `filterStatus`、`clearIssueFilters`、`taskFiltersActive`，确认无遗漏。
- `npm test` 全量。
- 如有 E2E，补一条筛选标签后列表变短（可选）。

**提交：** `chore: 筛选重构收尾`（仅当有零碎修正时）

---

## 执行方式

计划已保存到 `docs/plans/2026-04-03-issue-filter-linear-style.md`。

**1. 子代理驱动（当前会话）** — 按任务分派 subagent，任务间 review；需配合 `@superpowers/subagent-driven-development`。

**2. 并行会话（独立执行）** — 在 worktree 中新开会话，使用 `@superpowers/executing-plans` 按批次执行并在检查点汇报。

请选择 **1** 或 **2**。
