# Linear Lite - Phase 3 实施计划（核心轻量管理能力）

基于 `prd/phase3-ideas.md` 确定的演进目标：补齐项目管理、高密度列表视图与任务时间维度，使系统在任务量与项目量上升时仍保持可用。

## 目标摘要

1. **结构管理**：项目新建入口与项目设置（名称、标识符），闭环项目生命周期。
2. **效率升级**：Board / List 视图切换，列表视图高密度展示并支持按状态/优先级分组。
3. **时间维度**：任务增加 `dueDate`、`completedAt`，UI 展示截止日与超期提示、编辑时日期选择。

---

## Proposed Changes

### 1. Backend：领域模型与 API 扩展

#### 1.1 任务表扩展（时间字段）

- **数据库**：`tasks` 表新增字段
  - `due_date`：DATETIME NULL，预计完成/截止日期
  - `completed_at`：DATETIME NULL，实际完成时间（系统维护）
- **业务规则**：当任务 `status` 流转到 `done` 或 `canceled` 时，后端在更新逻辑中自动设置 `completed_at = NOW()`；从终态改回进行中时清空 `completed_at`。

#### 1.2 任务 API 行为

- **现有**：`GET /api/tasks?projectId=`, `POST /api/tasks`, `PUT /api/tasks/{taskKey}` 保持不变。
- **扩展**：
  - 请求/响应体增加 `dueDate`（ISO 8601 或毫秒时间戳，依现有约定）、`completedAt`（只读，由后端维护）。
  - `PUT` 更新状态时按上述规则维护 `completed_at`。

#### 1.3 项目 API 扩展

- **现有**：`GET /api/projects`, `POST /api/projects` 已存在（Phase 2）。
- **新增**：`PUT /api/projects/{id}`，请求体支持 `name`、`identifier`，用于项目设置页的修改。

### 2. Frontend：模型与 Store 对齐

- **Domain**：`Task` 类型增加 `dueDate?: number | null`、`completedAt?: number | null`（与现有 `createdAt`/`updatedAt` 风格一致，或统一用 ISO 字符串，与后端约定一致）。
- **projectStore**：增加 `createProject(name, identifier)` 调用 `POST /api/projects`；增加 `updateProject(id, payload)` 调用 `PUT /api/projects/{id}`；创建成功后刷新列表并可选设为当前项目。
- **taskStore**：创建/更新任务时携带 `dueDate`；从 API 响应中解析 `dueDate`、`completedAt` 并写入 state。

### 3. Frontend：List View 开发

- **视图切换**：主界面（如 BoardView 所在布局）右上角增加视图切换器：Board / List，切换时渲染 BoardView 或新的 ListView。
- **ListView**：新建 `TaskListView.vue`（或等价组件）
  - 高密度表格：列至少包含 ID(task_key)、Title、Status、Priority、Assignee、Due Date；可选 Completed At。
  - 支持按状态或优先级分组（折叠/展开的 Accordion 式），与 Board 列语义一致以便对照。
- **偏好记忆**：视图类型（board | list）写入 `localStorage`，进入主界面时恢复上次选择。

### 4. Frontend：UI 扩充

- **CreateProjectModal**：弹窗表单（项目名称、标识符），提交调用 `projectStore.createProject`，成功后关闭并刷新侧栏项目列表。
- **ProjectSettings**：轻量弹窗或内联表单，展示当前项目 name、identifier，可编辑并调用 `projectStore.updateProject`。
- **TaskCard**：展示截止日（若有）；若已超期（dueDate < 今日且未完成）则飘红或红点提示。
- **TaskEditor**：增加 Due Date 选择（Date Picker），创建/编辑时读写 `dueDate`；只读展示 `completedAt`（当有值时）。

---

## 验证计划 (Verification Plan)

### 自动化 / 脚本

- 提供 `schema` 变更脚本（如 `schema-v3-task-timeline.sql`）仅新增 `due_date`、`completed_at` 列，可对已有库执行；不破坏现有 data-init 与 Phase 2 数据。

### 人工验证

1. **项目新建与设置**：通过 CreateProjectModal 创建新项目，侧栏出现且可切换；在项目设置中修改名称/标识符，保存后侧栏与标题等处更新一致。
2. **列表视图**：切换到 List View，表格展示 ID/Title/Status/Priority/Assignee/Due Date；分组折叠/展开正常；再切回 Board，状态一致。
3. **截止日与完成时间**：创建/编辑任务设置 Due Date，卡片与列表均展示；将任务改为 Done，检查 `completedAt` 有值（API 或 UI 展示）；将任务改回进行中，`completedAt` 清空；超期未完成任务有红色提示。
4. **视图偏好**：选择 List 后刷新页面，仍为 List；改为 Board 后刷新，仍为 Board。

#### 人工执行清单与预期

| 项 | 步骤 | 预期结果 |
|----|------|----------|
| **1. 项目新建与设置** | ① 点击「新建项目」打开弹窗，填写名称与标识符并提交。② 侧栏出现新项目，切换后看板为该项目。③ 打开该项目设置，修改名称/标识符并保存。 | 新项目可创建且出现在侧栏；设置修改后侧栏与页面标题等展示更新。 |
| **2. 列表视图** | ① 右上角切换到 List View。② 检查表格列与分组。③ 切换回 Board。 | 列表高密度展示且可分组；切换回 Board 后数据一致。 |
| **3. 截止日与完成时间** | ① 创建任务并设置 Due Date。② 在卡片/列表中确认展示。③ 将任务改为 Done，确认完成时间出现。④ 改回 In Progress，完成时间清空。⑤ 设 Due Date 为过去日期且未完成，确认超期提示。 | 截止日与完成时间展示与后端规则一致；超期有明显提示。 |
| **4. 视图偏好** | ① 选 List 后刷新；② 选 Board 后刷新。 | 刷新后视图类型与上次选择一致。 |

---

## 与 Phase 2 的衔接

- 后端沿用 Spring Boot 3.x + MyBatis-Plus + MySQL；鉴权与 API 风格不变。
- 前端沿用 Pinia、Vue Router、现有 `api` 封装与 auth/project/task store 结构；仅扩展字段与接口、新增组件与视图。
