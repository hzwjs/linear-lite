# Linear Lite - Phase 7 实施计划（子任务 Sub-issues 能力）

基于 [prd/phase7-ideas.md](./prd/phase7-ideas.md) 与 [linear-subtasks-analysis.md](./linear-subtasks-analysis.md)。  
本阶段为 linear-lite 引入可用的父子任务能力：数据模型与 API、列表展示、详情 Sub-issues 区块与内联创建、多级与展示选项。

## 目标摘要

1. **数据与 API**：`tasks` 表增加 `parent_id`；创建/更新/列表/详情支持父子语义与子任务计数。
2. **列表**：支持「显示子任务」「嵌套子任务」；子任务行缩进、标题带父任务信息，父任务行显示 x/y。
3. **详情**：父任务 Sub-issues 区块为真实列表、可折叠、内联创建；子任务详情有父任务链接并可再建子任务。
4. **多级**：支持子任务再挂子任务；详情与列表均可选「仅直接」或「展开多级」，计数语义一致。
5. **视图状态**：上述展示选项与现有 view state 统一并持久化。

---

## Proposed Changes

### 1. Backend：数据模型与约束

#### 1.1 表结构

- 在 `tasks` 表增加列：`parent_id BIGINT NULL`，`FOREIGN KEY (parent_id) REFERENCES tasks(id) ON DELETE SET NULL`（或 ON DELETE CASCADE 由产品决定）。
- 增加索引：`CREATE INDEX idx_tasks_parent_id ON tasks (parent_id);`，便于按父查子、列表过滤「仅顶层」（`parent_id IS NULL`）。

#### 1.2 实体与 DTO

- `Task` 实体增加 `parentId`（Long，可为 null）。
- 创建/更新请求 DTO 支持 `parentId`；若传非空则校验对应父任务存在且不形成环（可选：限制深度上限）。
- 列表与详情响应中可增加 `subIssueCount`、`completedSubIssueCount`（或由前端按子任务列表聚合，视接口设计而定）。

#### 1.3 接口行为

- **创建任务**：接受 `parentId`，写入 `tasks.parent_id`；`task_key` 仍按项目维度自增，与父任务无关。
- **更新任务**：允许修改 `parentId`（含置空以「解除父子」）。
- **列表**：支持查询参数如 `topLevelOnly=true`（仅 `parent_id IS NULL`）或返回树形/扁平列表由前端组合；若列表一次返回所有层级，需在服务端或前端计算缩进与 x/y。
- **详情**：返回 `parentId`；若需子任务列表，可由详情接口返回 `children` 或由前端单独请求「某任务的子任务列表」。

---

### 2. Backend：子任务列表与计数

#### 2.1 子任务列表

- 提供「按父任务查子任务」的接口（如 `GET /tasks?parentId={id}` 或 `GET /tasks/{id}/sub-issues`），支持 `nested=true` 时递归返回所有后代（或由前端多级请求）。
- 若采用单次树形返回，需定义树节点结构（id, title, status, children, completed 等）以便前端渲染缩进与 x/y。

#### 2.2 计数

- 父任务的 x/y：在「仅直接子任务」模式下为直接子任务完成数/总数；在「嵌套」模式下为所有后代完成数/总数。可在后端聚合或由前端根据子任务列表计算，与列表/详情展示一致。

---

### 3. Frontend：类型与 API 调用

#### 3.1 类型扩展

- `Task`（或等效）增加 `parentId?: string | null`、`subIssueCount?: number`、`completedSubIssueCount?: number`（若后端返回）。
- 列表/详情请求与响应类型与后端 DTO 对齐。

#### 3.2 创建子任务

- 创建任务时支持传入 `parentId`；从父任务详情内联创建或从列表行「Add sub-issue」创建时，将当前任务 id 作为 `parentId`。

---

### 4. Frontend：视图状态

#### 4.1 扩展 view state

- 在现有视图配置（如 groupBy、orderBy、visibleProperties 等）中增加：
  - `showSubIssues: boolean`（列表是否显示子任务行，默认 true 或 false 由产品定）
  - `nestedSubIssues: boolean`（是否展示多级子任务，默认 true）
- 与 Phase 5/6 的 view state 存储与持久化方式一致（如 localStorage / 团队默认）。

#### 4.2 Display options

- 列表页 Display options（或等效入口）增加「显示子任务」「嵌套子任务」开关，读写上述 view state。

---

### 5. Frontend：列表子任务展示

#### 5.1 TaskListView

- 当 `showSubIssues` 为真时，列表数据需包含子任务（或由后端一次返回树形/扁平，或前端根据顶层任务再请求子任务并合并）。
- 子任务行相对父任务行缩进（多级则多级缩进）；行标题区展示「当前标题 > 父任务标题」或等效，便于区分归属。
- 父任务行在标题旁展示 x/y（完成数/总数）；若 `nestedSubIssues` 为假则仅计直接子任务，为真则计所有后代。

#### 5.2 行内「Add sub-issue」

- 列表行「Add sub-issue」按钮绑定逻辑：点击后打开创建入口（IssueComposer 或内联表单），并注入当前行任务为 `parentId`，默认状态等可继承当前行或保持现有 Composer 逻辑。

#### 5.3 BoardView（可选）

- 卡片上可展示子任务计数（x/y）；若本阶段不实现卡片内子任务列表，可仅做计数与 Phase 7 验收一致即可。

---

### 6. Frontend：详情 Sub-issues 区块

#### 6.1 数据与展示

- TaskEditor 打开父任务时，根据当前任务 id 请求子任务列表（直接子任务或根据「Nested sub-issues」请求多级）；替换当前占位文案为真实列表。
- 每条子任务：可点击进入该任务详情、展示完成态（勾选）、可选展示状态/负责人等（Display properties）。
- 区块支持折叠/展开（与现有「Collapse sub-issues section」一致）。

#### 6.2 内联创建

- 「Create new sub-issue」/「Add sub-issue」在区块内展开内联表单：标题必填，描述与状态/优先级/负责人等可选；提交时 `parentId` 为当前打开的任务。
- 创建成功后刷新子任务列表；可选提供「保存并继续添加」以连续创建。

#### 6.3 Display options（详情内）

- Sub-issues 区块的 Display options 增加「Nested sub-issues」：开启时树形展示所有后代（缩进+虚线或连线），关闭时仅直接子任务；计数（x/y）随展示模式变化。

---

### 7. Frontend：子任务详情与多级

#### 7.1 子任务详情

- 打开子任务时，若存在 `parentId`，在标题下或面包屑展示「Sub-issue of XXX」链接，点击跳转父任务详情。
- 子任务详情内同样保留 Sub-issues 区块与「Create new sub-issue」，新建时 `parentId` 为当前子任务，实现多级。

#### 7.2 多级展示一致性

- 列表与详情内「Nested sub-issues」语义一致：均为「是否展开/展示多级」；计数在两种模式下与展示范围一致。

---

### 8. 兼容与回归

- 无 `parent_id` 的任务视为顶层；现有列表/筛选/分组/排序逻辑在「仅顶层」或「含子任务」模式下均可用。
- 不改变现有新建、打开、保存、删除主流程，仅扩展参数与展示；删除父任务时子任务 `parent_id` 置空或级联删除需与后端约束一致。
- 验证：Phase 5/6 的视图配置、键盘导航、深链接、Board/List 切换等无回归。

---

## 验证计划（Verification Plan）

1. **数据**：创建父任务 A → 为 A 创建子任务 B、C → B 再建子任务 D；数据库与 API 返回的 `parent_id` 与层级正确。
2. **列表**：开启/关闭「显示子任务」时列表行数变化正确；开启/关闭「嵌套子任务」时多级子任务显示/隐藏正确；缩进与 x/y 正确。
3. **详情**：父任务 A 的 Sub-issues 区块显示 B、C（及可选 D）；内联创建新子任务后列表刷新；从 B 进入详情可见「Sub-issue of A」并可再建子任务 D。
4. **持久化**：刷新后「显示子任务」「嵌套子任务」选项保持。
5. **回归**：新建、筛选、分组、排序、打开、Board/List 切换、深链接无退化。
