# 任务标签 实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 按已批准设计实现项目级任务标签（`labels` + `task_labels`、侧栏输入即创建、API 与活动记录、前端自动保存）。

**方案概述：** 新增迁移与 MyBatis 实体/Mapper；`TaskService` 在列表/读写任务时批量加载并回填 `Task.labels`（`@TableField(exist = false)`）；更新/创建时解析 `labels` 数组整包替换关联；`ProjectController` 提供标签联想；`TaskActivity` 记录 `fieldName=labels`。前端扩展 `ApiTask`/`Task`、`taskApi`/`taskStore`、`TaskEditor` chip 输入。

**技术栈：** Spring Boot 3、MyBatis-Plus、MySQL 8；Vue 3、Pinia、axios、vue-i18n、Vitest。

**设计引用：** `docs/plans/2026-04-02-task-labels-design.md`

---

### 任务 1：数据库迁移与 schema.sql

**涉及文件：**

- 新增：`linear-lite-server/src/main/resources/migration-task-labels.sql`（名称与仓库现有 `migration-*.sql` 风格一致即可）
- 修改：`linear-lite-server/src/main/resources/schema.sql`（与迁移内容对齐，便于新环境）

**步骤 1：** 编写 DDL：`labels`（`id`, `project_id`, `name` VARCHAR(64), `created_at`，`UNIQUE (project_id, name)`，`idx_labels_project_id`）；`task_labels`（`task_id`, `label_id` 联合主键，`idx_task_labels_label_id`）。**不得**出现 `FOREIGN KEY`。

**步骤 2：** 在本地或 CI 使用的库上执行迁移 SQL（实现者环境），确认无语法错误。

**步骤 3：** 提交

```bash
git add linear-lite-server/src/main/resources/migration-task-labels.sql linear-lite-server/src/main/resources/schema.sql
git commit -m "chore(db): 任务标签表 labels 与 task_labels"
```

---

### 任务 2：Java 实体与 Mapper

**涉及文件：**

- 新增：`linear-lite-server/src/main/java/com/linearlite/server/entity/Label.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/entity/TaskLabel.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/mapper/LabelMapper.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskLabelMapper.java`

**步骤 1：** `Label` 对应表 `labels`，字段与表一致；`TaskLabel` 对应 `task_labels`（可用 `@TableId` 组合或单表无主键自增时按 MyBatis-Plus 项目惯例处理联合主键）。

**步骤 2：** Mapper 继承 `BaseMapper<>`，无额外 XML 若可通过 Lambda 完成。

**步骤 3：** 编译

```bash
cd linear-lite-server && mvn -q -DskipTests compile
```

预期：BUILD SUCCESS

**步骤 4：** 提交

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/entity/Label.java linear-lite-server/src/main/java/com/linearlite/server/entity/TaskLabel.java linear-lite-server/src/main/java/com/linearlite/server/mapper/LabelMapper.java linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskLabelMapper.java
git commit -m "feat(server): Label / TaskLabel 实体与 Mapper"
```

---

### 任务 3：DTO 与 Task 上的响应字段

**涉及文件：**

- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/TaskLabelItemRequest.java`（或等价名：内含 `Long id`、`String name`，Jackson 忽略互斥校验放 Service）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/dto/UpdateTaskRequest.java` — 增加 `List<TaskLabelItemRequest> labels`（允许 null）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/dto/CreateTaskRequest.java` — 同上
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/TaskLabelResponse.java`（`id`, `name`）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/entity/Task.java` — `@TableField(exist = false) private List<TaskLabelResponse> labels` + getter/setter

**步骤 1：** 保证 JSON 属性名为前端已有的 camelCase 惯例（`labels`，元素 `id`/`name`）。

**步骤 2：** `mvn -q -DskipTests compile`

**步骤 3：** 提交

```bash
git commit -m "feat(server): 任务标签请求/响应 DTO 与 Task 瞬态 labels"
```

---

### 任务 4：LabelService 与任务关联替换逻辑

**涉及文件：**

- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/LabelService.java`（或并入 `TaskService` 的 private 方法簇，以 YAGNI 选更小侵入处；独立类更易测）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`

**步骤 1：** 实现 `findOrCreateLabel(long projectId, String trimmedName)`（唯一冲突时查询复用）；`listByProjectForQuery(long projectId, String queryPrefix)`（上限如 100）；`replaceTaskLabels(long taskId, long projectId, List<TaskLabelItemRequest> items)`：校验每个元素**恰好** id 或 name；按 id 加载 `Label` 并校验 `project_id`；name 走 find-or-create；**删除** `task_id` 的旧行再 **批量插入**；`@Transactional`。

**步骤 2：** 在 `TaskService.create(...)` 末尾若 `request.getLabels() != null` 调用替换（新建任务已有 `tasks.id`）。

**步骤 3：** 在 `TaskService.update(...)` 中若 `request.getLabels() != null` 调用替换。

**步骤 4：** 列表/查询方法在返回 `Task` 列表前：按 `task_id` 批量查询 `task_labels` + `labels`，填充每个 `Task.setLabels`（**按标签名排序**）。

**步骤 5：** `mvn -q -DskipTests compile`

**步骤 6：** 提交

```bash
git commit -m "feat(server): 标签 find-or-create 与任务 labels 替换及列表回填"
```

---

### 任务 5：失败测试 — TaskService 标签行为

**涉及文件：**

- 修改：`linear-lite-server/src/test/java/com/linearlite/server/service/TaskServiceTest.java`
- 按需增加 mock：`LabelMapper`、`TaskLabelMapper` 或 mock `LabelService`（视注入方式）

**步骤 1：** 新增测试：`update` 传入 `labels` 时调用关联替换（可用 `verify`）；跨项目 `labelId` 时抛业务异常或 400（与现有 `update` 异常风格一致）。

**步骤 2：** 运行

```bash
cd linear-lite-server && mvn -q test -Dtest=TaskServiceTest
```

预期：新测试先红后绿（本任务要求实现任务 4 已通过后再写测试则直接绿）。

**步骤 3：** 提交

```bash
git commit -m "test(server): TaskService 标签更新行为"
```

---

### 任务 6：活动记录 labels

**涉及文件：**

- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java` — 在 `recordActivityForTaskChanges` 前后加载旧/新标签名集合；若不同则 `taskActivityService.recordFieldChange(taskId, userId, "labels", oldJoined, newJoined)`，格式为**排序后逗号拼接**（与 `taskActivity.ts` 展示兼容）。

**步骤 1：** 确保仅在 `request.getLabels() != null` 且实际有变更时写入，避免噪音。

**步骤 2：** `mvn -q test -Dtest=TaskServiceTest`

**步骤 3：** 提交

```bash
git commit -m "feat(server): 任务标签变更写入 task_activities"
```

---

### 任务 7：GET /api/projects/{id}/labels

**涉及文件：**

- 修改：`linear-lite-server/src/main/java/com/linearlite/server/controller/ProjectController.java`
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`（或 `LabelService` + Controller 调 `LabelService`）
- 新增或修改：DTO 列表项 `TaskLabelResponse` 复用

**步骤 1：** `GET /api/projects/{projectId}/labels?query=`：校验当前用户为项目成员；返回 `ApiResponse<List<TaskLabelResponse>>`。

**步骤 2：** 手工 curl 或现有集成测试风格验证（若有 TestRestTemplate 测试可加一条）。

**步骤 3：** 提交

```bash
git commit -m "feat(server): 项目标签联想 API"
```

---

### 任务 8：前端类型与 task API

**涉及文件：**

- 修改：`src/types/domain.ts` — `Task` 增加 `labels?: { id: number; name: string }[]`
- 修改：`src/services/api/types.ts` — `ApiTask`、`UpdateTaskRequest`、`CreateTaskRequest` 增加 `labels` 类型；定义 `TaskLabelRef = { id?: number; name?: string }` 且互斥由调用方保证
- 修改：`src/services/api/task.ts` — `toTask` 映射 `labels`；`update`/`create` body 透传
- 修改：`src/services/api/project.ts` — 新增 `listLabels(projectId: number, query?: string)`

**步骤 1：** `pnpm exec vue-tsc --noEmit` 或 `npm run build` 中的类型检查阶段

**步骤 2：** 提交

```bash
git commit -m "feat(web): ApiTask/Task 与 labels API 类型及 project 标签列表"
```

---

### 任务 9：taskStore 与自动保存 payload

**涉及文件：**

- 修改：`src/store/taskStore.ts` — `applyLocalTaskPatch` 处理 `updates.labels`；`updateTask` 内 `taskApi.update` 传入 `labels`（结构与设计一致：`(id|name)[]`）

**步骤 1：** 若有创建任务路径（`createTask`）需同样传 `labels`。

**步骤 2：** `pnpm test`（根目录）

预期：现有测试通过

**步骤 3：** 提交

```bash
git commit -m "feat(web): taskStore 合并与提交任务 labels"
```

---

### 任务 10：TaskEditor 侧栏标签 UI

**涉及文件：**

- 修改：`src/components/TaskEditor.vue` — 将「标签」占位 `button` 换为 chip 列表 + 输入；`onMounted`/watch `task.projectId` 时拉取联想；键盘添加/删除与防抖保存挂钩（与现有 `scheduleSave` 一致）

**步骤 1：** 无任务或 `projectId` 缺失时禁用或隐藏输入。

**步骤 2：** 手动在浏览器验证：增删标签、刷新后仍在。

**步骤 3：** 提交

```bash
git commit -m "feat(ui): TaskEditor 任务标签 chip 与联想"
```

---

### 任务 11：i18n 与活动文案

**涉及文件：**

- 修改：`src/i18n/messages/zh-CN.ts`、`src/i18n/messages/en.ts` — `fieldLabel.labels`（若尚无）
- 修改：`src/utils/taskActivity.ts` — 如 `labels` 的 `oldValue`/`newValue` 需分隔展示可加专用分支（可选，设计允许逗号串直接展示）

**步骤 1：** `pnpm test`

**步骤 2：** 提交

```bash
git commit -m "fix(i18n): 任务标签字段活动文案"
```

---

### 任务 12：全量验证

**步骤 1：**

```bash
cd linear-lite-server && mvn -q test
```

**步骤 2：**

```bash
pnpm test && pnpm run build
```

（根目录）

**步骤 3：** 若有 E2E 则运行；无则跳过。

---

## 执行交接

计划已保存到 `docs/plans/2026-04-02-task-labels.md`。

**方式 1 — 子代理驱动（当前会话）：** 按任务顺序执行，任务之间做简短 review；配合 `superpowers:subagent-driven-development`。

**方式 2 — 并行会话：** 在独立 worktree 中新开会话，使用 `superpowers:executing-plans` 按批次执行并在检查点汇报。

请选择方式 1 或方式 2。
