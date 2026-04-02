# 任务标签（按项目）设计

## 概述

为任务增加**项目级标签**：每个项目维护独立标签库；任务详情侧栏可**输入即创建**（项目内名称去重后关联），与现有「标签」占位 UI 对齐。存储采用 **`labels` + `task_labels`**，不使用数据库外键。

## 已确认决策

| 项 | 选择 |
|----|------|
| 作用域 | **B**：按项目隔离，任务仅能使用所属项目的标签 |
| 创建方式 | **A**：任务侧栏输入新名称即在该项目下 find-or-create 并关联；不要求单独标签管理页（MVP） |
| 存储方案 | **推荐**：`labels` 表 + `task_labels` 关联表（应用层校验 `project_id` 一致） |

## 1. 数据模型

### 表 `labels`

- `id` BIGINT 自增主键  
- `project_id` BIGINT NOT NULL（逻辑关联 `projects.id`，无 FK）  
- `name` VARCHAR(64) NOT NULL（trim 后存储；**唯一约束 `(project_id, name)`**）  
- `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP  
- 索引：`idx_labels_project_id ON labels (project_id)`  

### 表 `task_labels`

- `task_id` BIGINT NOT NULL（逻辑关联 `tasks.id`）  
- `label_id` BIGINT NOT NULL（逻辑关联 `labels.id`）  
- 主键：`(task_id, label_id)`  
- 索引：`idx_task_labels_label_id ON task_labels (label_id)`（便于后续按标签反查任务）  

**禁止**在 DDL 中使用 `FOREIGN KEY` / `REFERENCES`。

### 一致性

- 更新任务标签时：任务 `project_id` 必须与每个标签行的 `project_id` 一致，否则 **400**。  
- 单次更新在**同一事务**内：解析/创建标签 → 删除该任务旧关联 → 插入新关联。  

### MVP 外范围

- 不提供「删除项目标签定义」的独立能力（仅可从任务上移除关联）；颜色列不做。  
- 列表按标签筛选/分组为后续迭代。

## 2. API 与请求语义

### 任务响应

- 凡返回任务 JSON 的接口（列表、详情、创建、更新、favorite 等），在任务对象上增加 **`labels`**：`Array<{ id: number, name: string }>`。  
- 排序：建议**按标签名升序**，保证稳定、利于展示与活动记录对比。

### `PUT /api/tasks/{taskKey}`（`UpdateTaskRequest`）

- 可选字段 **`labels`**：  
  - **出现该字段** → 对该任务的 `task_labels` 做**整包替换**（非数组合并 patch）。  
  - 类型：`Array<{ id?: number, name?: string }>`，**每个元素恰好二选一**。  
  - `name`：trim 后非空、长度 ≤ 64；在项目内 **find-or-create**。  
  - `id`：必须存在且 `labels.project_id` 等于该任务的 `project_id`。  
  - `labels: []` → 清空；**省略 `labels`** → 不修改标签。  

### `POST /api/tasks`（`CreateTaskRequest`）

- 可选 **`labels`**，语义与上相同，在创建任务并生成主键后写入关联。

### 项目标签联想

- `GET /api/projects/{projectId}/labels?query=`  
  - `query` 可选：按名称前缀过滤（空则返回该项目下全部或上限条数，实现时约定如 100 条）。  
  - 权限与「能否访问该项目」一致（与现有项目成员校验对齐）。

## 3. 活动记录

- 当标签集合（按**名称**比较）变化时，记一条：`actionType = "changed"`，`fieldName = "labels"`。  
- `oldValue` / `newValue`：采用**稳定可读**的同一格式（推荐：标签名**排序后**用英文逗号拼接，与 `description` 等活动字段一样走现有 `recordFieldChange`）。  
- 前端：补充 `fieldLabel.labels`；`formatTaskValue`（或等价逻辑）中可对 `labels` 原样展示已拼接的字符串，避免裸 JSON。

## 4. 前端

- **`Task` / `ApiTask`**：增加 `labels?: { id: number; name: string }[]`；`taskApi.toTask` 映射。  
- **`TaskEditor.vue`**：侧栏「标签」由占位改为 **chip + 输入**；`GET .../labels` 做联想；回车/选择添加已有项，新名参与保存；删除 chip 即更新本地状态。  
- **保存路径**：与现有 **debounce 自动保存**、`store.updateTask` / 创建任务请求一致，在 payload 中带 `labels`。  
- **错误**：400（非法元素、跨项目 id、空名、超长名等）走现有全局错误/toast；失败保留本地编辑。

## 5. 测试建议

- 后端：`TaskService` 创建/更新带 `labels`、清空、`find-or-create`、跨项目 `id` 拒绝、活动记录 `labels` 一条。  
- 前端：类型与 mapper；可选：`formatTaskActivity` 对 `labels` 的展示单测。

## 6. 参考文件（实现时）

- 后端：`Task.java`、`TaskService.java`、`CreateTaskRequest.java`、`UpdateTaskRequest.java`、`ProjectController.java`、`TaskActivityService.java`、`schema.sql`、新建 migration。  
- 前端：`src/types/domain.ts`、`src/services/api/types.ts`、`src/services/api/task.ts`、`src/store/taskStore.ts`、`src/components/TaskEditor.vue`、`src/utils/taskActivity.ts`、`src/i18n/messages/*.ts`。

---

**状态：** 已确认定稿（2026-04-02）。实现见 `docs/plans/2026-04-02-task-labels.md`。
