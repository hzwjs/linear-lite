# 列表筛选（Linear 风 Add Filter）设计稿

**状态：** 已定稿（2026-04-03）  
**关联实现计划：** `2026-04-03-issue-filter-linear-style.md`

---

## 背景

当前 `BoardView` 使用单一 popover + 三个 `CustomSelect`（状态、优先级、负责人）。目标为对齐 Linear 式 **Add Filter**：分类入口、可扩展、子面板；并在第一期加入 **按标签筛选**。

## 目标与非目标

**目标**

- 顶栏 **Add Filter** 主面板：顶部搜索（过滤维度项名称）、分类项 **Status / Priority / Assignee / Labels**。
- 子面板：**桌面 hover + 点击** 展开；**Escape** 与现有筛选 popover 行为一致；子菜单 **不展示** 任务数量。
- **标签**：多选，语义为 **OR**（任务至少带有一个选中标签即通过）；未选标签时不增加过滤条件。
- **筛选仍在前端** 对已加载任务执行（与现有一致）；**不**新增后端统计或列表查询参数。
- **标签列表**：`GET /projects/{id}/labels`，支持子面板内搜索（沿用 API `query`）；按 `activeProjectId` 缓存，**切换项目** 时清空缓存与 `filterLabelIds`。
- **顶栏展示**：每个选中标签一颗 chip，可点删；`activeFilterCount` 中「有标签筛选」计 **1**（不按标签个数累加）。
- **清除**：`clearIssueFilters` 含标签；项目看板本地持久化（`projectBoardPreferences`）需读写 `filterLabelIds`。

**非目标（第一期）**

- AI Filter、Advanced filter、Agent、日期、项目属性等截图中的其他维度（不出现或仅占位/隐藏）。
- 子菜单内 issue 数量。
- 标签 AND 语义、「无标签」筛选。

## 架构取向

采用 **主壳 + 按维度拆子面板**：主菜单负责路由与搜索；Labels 独立（含 `listLabels`）；Status / Priority / Assignee 子面板内 **复用 `CustomSelect`**，降低回归风险。

## 数据与边界

- `taskStore.filterLabelIds: number[]`（去重有序可选，以 Set 实现再转数组亦可）。
- `filteredTasks`：若 `filterLabelIds.length > 0`，保留 `task.labels` 与选中 id 交集非空的任务。
- `stripProjectLabelFromTasks`：从任务上移除标签后，若某 id 不再被任何任务使用，可从 `filterLabelIds` 中剔除该 id（避免无效筛选残留）；**最小实现** 也可仅在切换项目时清空，本设计 **推荐** 同步剔除以一致。
- 无障碍：`role="dialog"`、焦点与键盘与现有 command bar popover 对齐。

## 测试

- `taskStore`：`filteredTasks` 在标签 OR 及与其它条件组合下的行为。
- 可选：新筛选菜单组件的 Escape / 子面板开关（Vitest + Testing Library）。
