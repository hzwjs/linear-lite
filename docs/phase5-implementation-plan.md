# Linear Lite - Phase 5 实施计划（交互模型重构与体验校准）

基于 [prd/phase5-ideas.md](./prd/phase5-ideas.md) 确定的演进目标：不再继续做表层 UI polish，而是围绕 `Create Issue`、`Open Issue`、`List / Board View System` 做一次交互模型层级的重构，使 Linear Lite 从“任务 CRUD 页面”升级为“以视图为中心的工作台”。

## 目标摘要

1. **Issue 创建重构**：默认新建从重型 `TaskEditor` 改为轻量 `IssueComposer`，统一顶部、列表分组、看板列头、快捷键与命令面板的创建体验。
2. **Issue 打开重构**：将当前 `TaskEditor` 从“遮罩表单抽屉”重构为更接近 `Issue Workspace` 的阅读优先详情面板，弱化表单感，保留主工作台上下文。
3. **视图系统重构**：建立统一的 `view state model`，让 List / Board 成为同一视图系统的两种投影，统一布局、分组、排序、显示属性与创建入口规则。
4. **高密度体验校准**：重排 List 行结构与 Board 列/卡片结构，降低组件感，提升键盘流、选中态与操作一致性。

---

## Proposed Changes

### 1. Frontend：Issue 创建从重表单切换到轻量 Composer

#### 1.1 新增 `IssueComposer` 组件

- 新建独立组件（建议如 `src/components/IssueComposer.vue`），承载轻量创建体验。
- 首屏仅强调：
  - `title`
  - 可选 `description`
  - 一排轻量属性控件：`status`、`priority`、`assignee`、`project`、`dueDate`
- 默认主动作：
  - `Create issue`
  - `Create more`（可选）

#### 1.2 统一所有创建入口

以下入口必须统一切入 `IssueComposer`：

- 顶部 `New Issue`
- List 分组头 `+`
- Board 列头 `+`
- 空状态创建入口
- 全局快捷键 `C`
- `CommandPalette` 的创建命令

统一规则：

- 只允许上下文默认值不同，不允许不同入口打开不同 UI
- 分组/列头入口需自动注入默认 `status`
- 当前项目上下文自动继承 `projectId`

#### 1.3 `TaskEditor` 不再承担默认新建职责

- 当前 `TaskEditor` 保留为任务详情/后续完善入口
- 新建流程成功后：
  - 默认关闭 composer
  - 可配置为打开新建成功的 issue workspace

### 2. Frontend：把 `TaskEditor` 重构为 `Issue Workspace`

#### 2.1 结构重组

当前 `TaskEditor` 是典型 form drawer。Phase 5 应重构为：

- 顶部：Issue ID、标题区、关闭、更多操作
- 主内容区：标题、描述、未来资源区/活动区占位
- 侧边属性区：状态、优先级、负责人、项目、截止日期、完成时间

#### 2.2 交互优先级调整

- 默认优先阅读，不默认呈现“表单正在编辑”的感觉
- 标题/描述在静态状态下像内容块，聚焦后才显编辑 affordance
- 属性变更保持轻量，尽量减少全局保存感

#### 2.3 主工作台上下文保留

- 弱化或移除大面积 dim overlay
- 保持列表/看板可见，并尽量保留其滚动位置与选择状态
- 路由深链接仍保留 `/tasks/:taskId`

### 3. Frontend：建立统一的 View State Model

#### 3.1 抽取视图配置状态

在现有 `viewModeStore` 基础上升级为更完整的视图配置状态，至少包含：

- `layout: 'list' | 'board'`
- `groupBy`
- `orderBy`
- `orderDirection`
- `visibleProperties`
- `showEmptyGroups`
- `completedVisibility`

#### 3.2 统一 List / Board 的数据投影规则

- List 与 Board 共用：
  - 分组逻辑
  - 排序逻辑
  - 当前筛选结果
  - 创建入口上下文
  - 属性显隐配置

#### 3.3 顶部 Header 重构为 View Bar

将 `BoardView.vue` 头部从“普通页面功能栏”升级为“视图控制条”：

- 当前项目 / 当前视图上下文
- 搜索
- Filter
- Display
- New Issue

`Board / List` 切换应弱化为视图布局的一部分，而不是单独高亮按钮的唯一主轴。

### 4. Frontend：List View 重排为高密度任务扫描器

#### 4.1 行结构重排

建议将任务行重构为：

- 左侧：selection / state toggle / priority icon
- 中部：title + 次级上下文（项目、PR、assignee 等按显示配置决定）
- 右侧：时间信息 / due date / 关键属性

#### 4.2 显示属性配置化

不再把行内字段硬编码为固定 pills 组合。应允许通过 `visibleProperties` 控制展示：

- ID
- Status
- Priority
- Assignee
- Project
- Due Date
- Updated At
- PR / Link

#### 4.3 键盘与选中态

List 至少支持：

- 上下选中
- `Enter` 打开 issue
- `Esc` 关闭 workspace
- hover / focus / selected 三态区分

### 5. Frontend：Board View 去组件化并接入统一视图系统

#### 5.1 列头强化

Board 列头成为主要结构，至少包含：

- 状态 icon
- 状态名
- count
- menu
- inline create

#### 5.2 卡片降噪

`TaskCard` 继续降低组件感：

- 更薄的边框与圆角
- 更少的块感
- 保留最少识别信息

#### 5.3 列内新建接入同一 composer

- 所有列头 `+` 统一打开 `IssueComposer`
- 自动注入对应列的默认状态
- 创建后新任务直接落在该列

### 6. Store / Routing / Overlay：交互骨架调整

#### 6.1 Overlay 管理升级

当前 `overlayStore` 已支持 Esc 关闭。Phase 5 需要明确分层：

- `IssueComposer`
- `IssueWorkspace`
- `CommandPalette`
- 项目级 modal

保证它们的开关优先级一致。

#### 6.2 任务选择状态与视图状态分离

- `currentTaskId`
- `isComposerOpen`
- `composerDefaults`
- `viewConfig`

应避免继续混在 `BoardView.vue` 的局部状态中。

#### 6.3 路由与视图联动

- 保持直接访问 `/tasks/:taskId` 可打开 issue
- 保持从列表/看板切换后任务上下文尽量不丢失

### 7. 文档与验证更新

#### 7.1 文档

- 更新 PRD / phase 文档之间的引用关系
- 如实现策略与 Phase 4 既有描述冲突，需在相关文档中显式标注“Phase 5 supersedes Phase 4 UX assumptions”

#### 7.2 验证

Phase 5 完成后至少验证：

1. 顶部、List、Board、快捷键创建是否进入同一 `IssueComposer`
2. 分组/列头上下文是否能正确继承默认状态
3. 打开 issue 后是否保留主工作台上下文
4. List / Board 是否共享同一视图配置逻辑
5. 键盘导航与 Esc/Enter 行为是否稳定

---

## 验证计划（Verification Plan）

### 人工验证

#### 1. 新建 Issue

1. 点击顶部 `New Issue`
2. 点击 List 分组头 `+`
3. 点击 Board 列头 `+`
4. 按 `C`
5. 从 `CommandPalette` 选择创建命令

预期：

- 均进入同一套 `IssueComposer`
- List / Board 分组上下文能注入默认状态
- 只填标题即可创建
- 创建成功后任务出现在正确分组/列

#### 2. 打开 Issue

1. 从 List 打开任务
2. 从 Board 打开任务
3. 直接访问 `/tasks/:taskId`

预期：

- 均打开统一的 `IssueWorkspace`
- 主工作台仍可见或保持明确上下文
- 标题/描述体验以阅读优先
- 属性编辑为轻量动作

#### 3. List / Board 视图系统

1. 切换 List / Board
2. 调整分组、排序、显示属性
3. 验证搜索/筛选后表现

预期：

- 两种布局共享视图配置
- 视图切换不改变核心筛选结果
- 显示属性控制实际影响两个布局

#### 4. 键盘与状态

1. List 中上下移动选中
2. `Enter` 打开 issue
3. `Esc` 关闭最上层面板

预期：

- 焦点与选中态清晰
- 键盘流不中断

---

## 与 Phase 4 的衔接

- Phase 4 的设计令牌、CustomSelect、CustomDatePicker、CommandPalette、列头 `+` 等基础设施继续复用。
- Phase 5 会 **覆盖** Phase 4 中对 `TaskEditor`、Header、List/Board 结构的部分假设。
- 后端 API 与领域模型在本阶段不是主要矛盾，Phase 5 以交互与视图系统重构为主。
