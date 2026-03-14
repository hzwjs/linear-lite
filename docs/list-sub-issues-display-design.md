# 任务列表中子任务展示设计

## 1. 目标

在列表视图中展示子任务行，使用户能在同一列表中看到父任务与子任务的层级关系，并保持分组（按状态/优先级等）、排序、单行行内信息等现有能力。

## 2. 现状

- **数据**：`viewConfig.showSubIssues` 为 true 时，`fetchTasks()` 使用 `topLevelOnly: false`，拉取全量任务（含子任务）；为 false 时仅拉取顶层任务。
- **分组与行**：`buildTaskGroups()` 在 `showSubIssues` 为 true 时为每个分组生成 `group.rows`（TaskRow[]），每个 TaskRow 含 `task`、`depth`（0=顶层，1+=子级）、可选 `parentTitle`。
- **列表渲染**：TaskListView 通过 `groupListRows(group)` 使用 `group.rows ?? group.tasks.map(t => ({ task: t, depth: 0 }))`，并按 `row.depth` 做缩进（paddingLeft）。
- **问题**：父子关系依赖 `task.parentId` 与父的 `task.id` 匹配；前端 `parentId` 实际为后端数据库 id 的字符串，`task.id` 为 task_key（如 ENG-5），二者不一致导致子任务从未被归入父任务，列表只显示顶层任务。

## 3. 设计原则

- **数据一致**：父子关系用后端数据库 id 维度的 parentId 与 numericId 在应用层维护，列表构建时按 numericId/parentId 匹配。
- **保持单行**：每行仍为一条任务（父或子），子任务通过缩进表达层级，不改为多行合并或卡片嵌套。
- **与详情一致**：列表中的「子任务」与详情侧栏 Sub-Issues 区块的数据源和层级语义一致（直接子任务 + 可选嵌套）。

## 4. 展示规则

| 项目 | 规则 |
|------|------|
| **何时展示子任务** | 视图配置 `showSubIssues === true` 时展示；关闭时仅展示顶层任务。 |
| **层级** | 顶层任务 depth=0；直接子任务 depth=1；若开启「Nested sub-issues」，孙级及更深为 depth=2, 3, …。 |
| **缩进** | 按 depth 线性缩进，例如 `paddingLeft: calc(12px + depth * 20px)`，与当前实现一致。 |
| **分组** | 子任务跟随其**自身状态/优先级等**进入对应分组（与父任务可能在不同组）。 |
| **排序** | 组内先按顶层任务当前排序规则排序，每个父任务下其子任务按同一规则排序并紧跟该父任务；嵌套时递归同一规则。 |
| **行内容** | 与现有列表行一致：ID、状态图标、标题（子任务可带 parentTitle 如「标题 > 父标题」）、子任务数（仅 depth=0 且存在时）、指派人、截止日等。 |
| **交互** | 点击行打开该任务详情；父任务行可展示「Add sub-issue」等，与现有行为一致。 |

## 5. 数据与构建逻辑

- **顶层**：`source.filter(t => t.parentId == null)`，按 groupBy 分组得到各组的顶层任务列表。
- **子任务行**：对每个顶层任务，用其 `numericId`（后端主键）在 source 中找子任务：`parentId === String(parentNumericId)`；若开启嵌套，递归用子任务的 `numericId` 再找下一层，depth 递增。
- **Task 字段**：`parentId` 存后端 parent_id 的字符串形式（与 numericId 同源）；`numericId` 为自身后端 id；列表构建仅用 numericId/parentId，不再用 task_key 作父子键。

## 6. 视图配置

- **显示子任务**（showSubIssues）：列表是否包含子任务行；关闭时仅拉取并展示顶层任务。
- **嵌套子任务**（nestedSubIssues）：为 true 时展示多级子任务（递归 depth）；为 false 时仅展示直接子任务（仅 depth=1）。

## 7. 实现要点

1. **taskView.ts**  
   - `getDescendantRows(allTasks, parentNumericId: number, parentTitle, nested, config)`：子集为 `allTasks.filter(t => t.parentId != null && String(t.parentId) === String(parentNumericId))`；递归时传入 `task.numericId`。  
   - `buildTaskGroups` 中在 `showSubIssues` 时对每个顶层任务调用 `getDescendantRows(source, task.numericId!, task.title, config.nestedSubIssues, config)` 并拼入 `group.rows`。

2. **TaskListView**  
   - 已支持 `group.rows` 与 depth 缩进，无需改结构；仅需保证传入的 `groups` 中 `rows` 正确包含子任务行。

3. **后端/前端约定**  
   - 列表接口在 `topLevelOnly: false` 时返回全量任务，每条任务带 `parentId`（Long）、主键 id；前端 toTask 将 id 映射为 numericId、parentId 转为字符串，用于列表构建与详情一致。

## 8. 后续可选增强

- **按父折叠**：在分组内支持「折叠/展开」某父任务，仅显示其子任务行或隐藏，以简化长列表。
- **子任务数**：已在父任务行展示「已完成/总数」；若需在子任务行展示其自身的子任务数，可复用同一字段与样式。
