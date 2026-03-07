# Phase 3 多智能体并行派发计划

基于 `phase3-task-list.md` 与 Phase 2 的 dispatching 原则：按依赖拆成多波，同一波内无依赖的任务由多 Agent 并行执行。

---

## 前置假设

- Phase 2 已完成：后端 `linear-lite-server` 与前端 `src/` 已就绪，GET/POST projects、Task CRUD、Auth、Sidebar、BoardView 均可用。
- Phase 3 仅做领域扩展与新增视图/组件，不改变现有鉴权与目录结构。

## 派发约束（硬性）

- **不允许 Agent 在文档/假设下写代码**。仅当某波次的前置条件已在代码库中真实实现、且可通过运行或接口验证时，才可派发该波次的 Agent。
- 派发前必须确认：依赖波次对应的任务（见 `phase3-task-list.md`）已勾选完成，且相关 API、类型、Store 等已存在并可调。

---

## 波次总览

| 波次 | 内容 | Agent 数 | 依赖 |
|------|------|----------|------|
| Wave 1 | 后端：任务时间字段 + 项目 PUT (P3-1.x) | 1 | 无 |
| Wave 2 | 前端：Store 与领域模型对齐 (P3-2.x) | 1 | Wave 1 |
| Wave 3a | 前端：List View (P3-3.x) | 1 | Wave 2 |
| Wave 3b | 前端：项目 UI + 任务卡片/编辑器时间 (P3-4.x) | 2 并行 | Wave 2 |
| Wave 4 | 验证与收尾 (P3-5.x) | 1 | Wave 3a + Wave 3b |

---

## Wave 1：后端领域扩展（单 Agent）

**任务 ID**：P3-1.1～P3-1.6

**目标**：任务表增加 due_date、completed_at；任务 API 与 Service 支持时间字段及终态自动写 completed_at；项目支持 PUT 更新。

**约束**：
- 仅修改 `linear-lite-server` 内文件；不修改前端。
- Schema 变更以增量脚本形式提供（如 `schema-v3-task-timeline.sql`），不强制重做全量 schema。

**交付**：
- `tasks` 表新增 `due_date`、`completed_at`（DATETIME NULL）；实体与 Mapper 映射。
- TaskService：创建/更新时读写 dueDate；status 变为 done/canceled 时设置 completed_at，离开终态时清空。
- TaskController/DTO：GET/POST/PUT 请求与响应包含 dueDate、completedAt。
- `PUT /api/projects/{id}`，请求体支持 name、identifier；ProjectService.update。

**完成时返回**：列出新增/修改的文件、接口 URL 及请求/响应示例；schema 脚本路径与执行说明。

---

## Wave 2：前端 Store 与模型对齐（单 Agent）

**任务 ID**：P3-2.1～P3-2.5

**前置条件**：Wave 1 已完成，任务与项目 API 已扩展。

**目标**：前端 Task 类型与 task/project API、store 支持 dueDate、completedAt 与项目更新。

**交付**：
- `src/types/domain.ts`：Task 增加 dueDate、completedAt。
- task API：创建/更新/列表请求与响应透传 dueDate、completedAt。
- project API：新增 updateProject(id, payload)。
- projectStore：createProject 后刷新列表；新增 updateProject。
- taskStore：创建/更新传 dueDate；state 与 API 解析 dueDate、completedAt。

**约束**：不实现 List View、不新增 CreateProjectModal/ProjectSettings、不修改 TaskCard/TaskEditor 的 UI（仅数据层就绪）。

**完成时返回**：列出修改/新增文件及 API/store 方法对应关系。

---

## Wave 3a：List View（单 Agent）

**任务 ID**：P3-3.1～P3-3.5

**前置条件**：Wave 2 已完成，taskStore 已含 dueDate、completedAt。

**目标**：主界面 Board/List 切换、ListView 高密度表格与分组、视图偏好持久化。

**交付**：
- 主界面右上角视图切换器（Board / List），控制当前视图 state。
- 视图类型写入 localStorage，进入时恢复。
- `TaskListView.vue`：表格列 ID、Title、Status、Priority、Assignee、Due Date；按状态或优先级分组（折叠/展开）。
- 主区域根据视图类型渲染 BoardView 或 TaskListView；列表行点击打开 TaskEditor。

**约束**：不实现 CreateProjectModal、ProjectSettings、TaskCard/Editor 的日期 UI。

**完成时返回**：列出新增/修改的组件与路由（若有）、localStorage key 约定。

---

## Wave 3b：项目与任务 UI（2 Agent 并行）

**前置条件**：Wave 2 已完成。

### Agent 3b-1：项目新建与设置

**任务 ID**：P3-4.1～P3-4.4

**范围**：新建项目弹窗、项目设置弹窗及入口。

**交付**：
- CreateProjectModal：name、identifier 表单，提交 projectStore.createProject，成功后关闭并刷新侧栏。
- 侧栏或头部「新建项目」入口打开该弹窗。
- ProjectSettings 弹窗：展示并编辑当前项目 name、identifier，提交 updateProject。
- 侧栏项目入口打开项目设置（如菜单项或设置图标）。

**约束**：不修改 TaskCard、TaskEditor、ListView。

**完成时返回**：列出新增组件与入口位置。

---

### Agent 3b-2：任务卡片与编辑器时间

**任务 ID**：P3-4.5～P3-4.7

**范围**：TaskCard 截止日与超期提示；TaskEditor Due Date 选择与 completedAt 展示。

**交付**：
- TaskCard：展示 dueDate；若 dueDate 已过且未完成则超期飘红/红点。
- TaskEditor：Due Date 选择器（Date Picker），创建/编辑时绑定 dueDate。
- TaskEditor：只读展示 completedAt（有值时）。

**约束**：不修改 List View、项目相关 Modal。

**完成时返回**：列出修改的组件与字段绑定说明。

---

## Wave 4：验证与收尾（单 Agent）

**任务 ID**：P3-5.1～P3-5.5

**前置条件**：Wave 3a、Wave 3b 均已完成。

**交付**：
- 执行 schema 变更脚本，确认新字段与现有数据兼容。
- 按 phase3-implementation-plan 中人工验证四项执行（项目新建与设置、列表视图、截止日与完成时间、视图偏好），并记录结果。
- 更新 README：Phase 3 能力与 schema 升级说明。

**完成时返回**：验证结果摘要与 README 变更说明。

---

## 执行顺序

1. **首轮**：派发 Wave 1（1 个 Agent）。
2. **Wave 1 完成后**：派发 Wave 2（1 个 Agent）。
3. **Wave 2 完成后**：并行派发 Wave 3a（1 个 Agent）与 Wave 3b（2 个 Agent：3b-1、3b-2）。
4. **Wave 3a 与 Wave 3b 全部完成后**：派发 Wave 4（1 个 Agent）。

派发前确认对应波次前置条件已满足（代码已落地、后端可启动、相关 API 可调）；不得在仅凭文档或假设的情况下派发写代码的 Agent。
