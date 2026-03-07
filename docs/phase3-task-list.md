# Phase 3 任务清单（核心轻量管理能力）

基于 `prd/phase3-ideas.md` 与 `phase3-implementation-plan.md` 拆解。执行顺序建议：Backend 领域扩展 → 项目 PUT API → 前端 Store/模型 → List View → 项目与任务 UI 扩充。

**图例**：`[ ]` 未开始 | `[x]` 已完成 | 依赖用 `←` 标注。

---

## 0. 前置（无新增决策项，沿用 Phase 2）

Phase 2 技术栈与目录结构已确认，Phase 3 直接在此基础上扩展。

---

## 1. 后端：任务时间字段与项目更新 API

### 1.1 数据库与实体

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-1.1 | 编写 schema 变更：tasks 表增加 due_date、completed_at (DATETIME NULL) | [x] | 无 |
| P3-1.2 | 实体 Task 增加 dueDate、completedAt 字段；Mapper/XML 映射 | [x] | P3-1.1 |
| P3-1.3 | TaskService：创建/更新任务时读写 dueDate；状态改为 done/canceled 时写 completed_at，离开终态时清空 | [x] | P3-1.2 |

### 1.2 任务 API 扩展

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-1.4 | TaskController/DTO：GET/POST/PUT 请求与响应包含 dueDate、completedAt | [x] | P3-1.3 |

### 1.3 项目 API 扩展

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-1.5 | 新增 UpdateProjectRequest DTO；实现 PUT /api/projects/{id}（更新 name、identifier） | [x] | Phase 2 ProjectController |
| P3-1.6 | ProjectService 实现 update(id, name, identifier) | [x] | P3-1.5 |

---

## 2. 前端：Store 与领域模型对齐

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-2.1 | domain Task 类型增加 dueDate、completedAt（类型与 createdAt/updatedAt 一致） | [x] | 无 |
| P3-2.2 | task API 与 DTO：创建/更新/列表响应透传 dueDate、completedAt | [x] | P3-1.4 |
| P3-2.3 | project API：新增 updateProject(id, payload) 调用 PUT /api/projects/{id} | [x] | P3-1.5 |
| P3-2.4 | projectStore：createProject 已存在则确保调用后刷新列表；新增 updateProject action | [x] | P3-2.3 |
| P3-2.5 | taskStore：创建/更新任务时传 dueDate；state 与 API 响应解析 dueDate、completedAt | [x] | P3-2.1, P3-2.2 |

---

## 3. 前端：List View

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-3.1 | 主界面右上角增加视图切换器（Board / List），控制当前视图类型 state | [x] | 无 |
| P3-3.2 | 视图类型持久化：localStorage 读写，进入主界面时恢复 | [x] | P3-3.1 |
| P3-3.3 | 新建 TaskListView.vue：表格列 ID、Title、Status、Priority、Assignee、Due Date | [x] | P3-2.5 |
| P3-3.4 | ListView 支持按状态或优先级分组，折叠/展开（Accordion） | [x] | P3-3.3 |
| P3-3.5 | 主区域根据视图类型渲染 BoardView 或 TaskListView；列表行可点击打开 TaskEditor | [x] | P3-3.1, P3-3.3 |

---

## 4. 前端：项目与任务 UI 扩充

### 4.1 项目

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-4.1 | 新增 CreateProjectModal：表单 name、identifier，提交调用 projectStore.createProject，成功后关弹窗并刷新侧栏 | [x] | P3-2.4 |
| P3-4.2 | 侧栏或头部提供「新建项目」入口，打开 CreateProjectModal | [x] | P3-4.1 |
| P3-4.3 | 新增 ProjectSettings 弹窗/页面：展示并编辑当前项目 name、identifier，提交调用 updateProject | [x] | P3-2.4 |
| P3-4.4 | 提供进入项目设置的入口（如侧栏项目右键或设置图标） | [x] | P3-4.3 |

### 4.2 任务卡片与编辑

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-4.5 | TaskCard：展示 dueDate（若有）；若 dueDate 已过且未完成则超期飘红/红点 | [x] | P3-2.5 |
| P3-4.6 | TaskEditor：增加 Due Date 选择（Date Picker），创建/编辑时绑定 dueDate | [x] | P3-2.5 |
| P3-4.7 | TaskEditor：只读展示 completedAt（当有值时） | [x] | P3-2.5 |

---

## 5. 验证与收尾

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P3-5.1 | 本地执行 schema 变更脚本，确认 tasks 新字段存在且兼容现有数据 | [ ] | P3-1.1（脚本已就绪，需本地执行） |
| P3-5.2 | 项目新建与设置：创建项目、修改名称/标识符，侧栏与展示一致 | [ ] | P3-4.2, P3-4.4 |
| P3-5.3 | 列表视图：Board/List 切换、分组、偏好记忆、刷新后恢复 | [ ] | P3-3.5, P3-3.2 |
| P3-5.4 | 截止日与完成时间：设置 dueDate、状态改 Done 后 completedAt、改回进行中清空、超期提示 | [ ] | P3-4.5, P3-4.6, P3-4.7 |
| P3-5.5 | 更新 README：Phase 3 新增能力与可选 schema 升级说明 | [x] | P3-5.1 |

---

## 完成标准（Phase 3 验收）

- [ ] 用户可新建项目并在项目设置中修改名称与标识符
- [ ] 主界面可在 Board 与 List 视图间切换，列表为高密度表格且支持分组，视图偏好可记忆
- [ ] 任务支持截止日（dueDate），卡片与编辑器中可设置与展示；完成/取消时自动记录 completedAt，离开终态时清空
- [ ] 超期未完成任务有明确视觉提示

---

## 任务依赖简图

```
P3-1.1 (schema)
  → P3-1.2 (实体/Mapper)
       → P3-1.3 (TaskService 时间逻辑)
            → P3-1.4 (Task API 扩展)
P3-1.5 → P3-1.6 (Project PUT)

P3-2.1, P3-2.2, P3-2.3
  → P3-2.4 (projectStore)
  → P3-2.5 (taskStore 时间字段)
       → P3-3.x (List View)
       → P3-4.x (项目 UI + 任务卡片/编辑器)

P3-3.x, P3-4.x → P3-5.x (验证)
```
