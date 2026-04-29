# 跨项目迁移任务 设计方案

- 日期：2026-04-28
- 状态：Draft（v1 设计）
- 涉及模块：`linear-lite-server`（tasks / labels / project_task_seq / activities）、前端 `TaskEditor.vue` / `TaskListView.vue` / `services/api/task.ts`

## 一、目标与范围

**目标**：允许有权限的成员将一个或一组任务从项目 A 迁移到项目 B，同时保留尽可能多的关联数据（评论、附件、活动、收藏、子任务、进度等）。

**非目标（v1 不做）**：
- 跨工作空间 / 跨账户迁移
- 复制语义（保留原任务再生成副本）——本期只做"移动"
- 批量历史回滚 / undo（活动日志可追溯，但不做一键回滚）

## 二、核心设计抉择

### 1. `task_key` 必须重新生成
`task_key = 项目 identifier + '-' + 项目内序号`（如 `ENG-1`）。迁移到项目 B 后，原 `ENG-1` 在新项目下不再合法（identifier 不属于 B、序号也未必能用），必须按 B 的 `project_task_seq` 重新分配序号并生成新 key（如 `PROD-42`）。

- **关联引用安全**：内部所有关联均使用 `task_id`（自增主键）而非 `task_key`，因此 `comments / attachments / activities / favorites / labels` 都不需要级联更新。
- **外部引用兼容**：新增 `task_key_aliases (old_key, task_id)` 表，`taskQueryService.getByKeyOrThrow` 命中失败时兜底查询，使旧 URL `/tasks/ENG-1` 仍能定位到任务。

### 2. 子任务必须整树一起迁移
`parent_id` 指向同一 `tasks.id`；当前 `TaskCommandService.update` 强制 `parent.projectId == existing.projectId`（`TaskCommandService.java:250`）。如果只迁父任务会造成跨项目父子，违反不变式。

- **策略**：迁移目标任务时递归收集其所有后代（复用 `TaskCommandService#getDescendantIds` 思路下移到公共 util），整体一起换 `project_id`，每个节点单独分配新 `task_key`。
- **批量迁移多个根**：只接受"森林根节点"集合，禁止同时传父和它的某个后代。

### 3. Labels（项目级词典）—— 名称映射
`labels` 表 `UNIQUE (project_id, name)`，迁移后旧 `task_labels.label_id` 仍指向 A 项目的 label，语义上不应跨项目。

- **默认策略 `remap`**：对每个旧标签 name，在 B 项目 `getOrCreateByName`（复用 `LabelService`），重写 `task_labels`。
- **可选 `drop`**：直接 `DELETE task_labels`，不在 B 项目创建。
- **活动日志**：写一条 `labels` 字段变更（旧名集合 → 新名集合）。

### 4. Assignee / Mentions —— 成员校验
- **Assignee**（请求体可选三种策略）：
  1. `unassign`（默认）：清空 `assignee_id`，不写 `assignee_display_name`。
  2. `keepAsDisplayName`：清空 `assignee_id`，把用户的 displayName 写入 `assignee_display_name`（参考导入路径用法）。
  3. `failFast`：直接 4xx 报错，让用户先在 B 加成员。
- **Comment mentions**：`comment_mentions` 仅作通知索引，不影响展示；保留不动。被提及人若不在 B 仍可见原评论文本，但不再产生新通知（这是历史事实）。

### 5. 权限
当前 `TaskPermissionGuard.requireProjectMember` 已按项目维度校验。迁移要求**同时**满足：
- 操作者是 **A 项目成员**（有权"搬走"），且
- 操作者是 **B 项目成员**（有权"放进去"）。

未来若引入项目内角色，可收紧到"管理员/项目所有者"。v1 先用"双边成员"。

### 6. 收藏 / 附件 / 评论 / 活动
- `task_favorites`、`task_attachments`、`task_comments`、`comment_mentions`、`task_activities`：全部按 `task_id` 关联，**无需迁移**，自动跟随。
- 仅追加一条 `task_activities`：`action_type=moved`、`field_name=project`、`old_value=A.identifier`、`new_value=B.identifier`，便于审计。

### 7. 状态/进度/日期等业务字段
原样保留（`status / priority / progress_percent / due_date / planned_start_date / completed_at / creator_id`）。`creator_id` 不变（迁移≠重建）。`updated_at` 自然更新。

## 三、数据库改动

仅一张新表（用于 URL 兼容）：

```sql
CREATE TABLE IF NOT EXISTS task_key_aliases (
    old_key    VARCHAR(32) NOT NULL PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_task_key_aliases_task_id ON task_key_aliases (task_id);
```

迁移时把每个被改 key 的旧值写入此表。`getByKeyOrThrow` 先查 `tasks.task_key`，未命中再查 alias 兜底；命中后响应附 `redirectedFrom` 字段（前端 toast 提示新 key）。

## 四、后端 API

### 端点

```
POST /api/tasks/{taskKey}/move
```

请求体：

```json
{
  "targetProjectId": 12,
  "labelStrategy": "remap",                  // remap (默认) | drop
  "assigneeStrategy": "unassign",            // unassign (默认) | keepAsDisplayName | failFast
  "includeDescendants": true                 // 默认 true，false 时如有子任务则报错
}
```

响应：返回新的根任务（`Task`，含新 `task_key`），并附 `movedCount`、`keyMap: { oldKey: newKey }`。

### 服务编排
新增 `TaskMoveService`（独立于 `TaskCommandService`，避免后者继续膨胀）。核心流程在单事务内：

1. **加载并校验**：根任务存在 → 双边成员校验 → 目标项目存在 → 不允许迁到同项目。
2. **收集子树**：BFS 拿到全部后代 `task_id`（复用 `getDescendantIds` 模式，下移到公共 util）。
3. **预留序号**：调用 `taskSequenceService.reserveTaskNumbers(targetProjectId, target.identifier, n)`，一次预留 `n = 子树大小`。
4. **重写 task_key**：按稳定顺序（如按 `id` 升序）依次分配。原 key 写入 `task_key_aliases`。
5. **更新 project_id**：批量 `UPDATE tasks SET project_id=?, task_key=? WHERE id IN (...)`。
6. **Label remap**：若 `labelStrategy=remap`，按名称在目标项目 `getOrCreate`，重写 `task_labels`；`drop` 时直接 `DELETE`。
7. **Assignee 处理**：按策略批量处理不在 B 中的 assignee。
8. **写活动日志**：每个被移动任务追加 `moved` 活动条目；被改的 label/assignee 也按现有 `recordFieldChange` 模式记录。
9. **返回根任务 + keyMap**。

错误统一使用现有 `IllegalArgumentException` / `ResourceNotFoundException`，由全局异常映射成 4xx。

## 五、前端改动

### 入口
- `TaskEditor.vue`：标题栏右侧"更多操作"菜单加一项「移动到其他项目…」（仅当用户在源项目有权限时显示）。
- `TaskListView.vue`：批量选中行时的批量操作菜单加「移动到…」。

### 移动对话框（新建组件 `MoveTaskModal.vue`）
- **目标项目下拉**：仅列出当前用户为成员的其他项目（复用 `projectApi.list()`）。
- **预览区**：展示 N 个任务将被移动、N 个 assignee 将被解绑、N 个标签将被映射或丢弃。
- **策略选项**：标签 = `remap` / `drop`，assignee = `unassign` / `keepAsDisplayName`。
- **确认后**调用 `taskApi.move(taskKey, payload)`。
- **路由处理**：当前 URL 含 `task_key` 时，迁移成功后用 `router.replace` 切到新 `task_key`，并 toast 显示 `ENG-1 → PROD-42`。

### API 客户端（`src/services/api/task.ts`）
新增：

```ts
move(taskKey: string, body: MoveTaskRequest): Promise<MoveTaskResponse>
```

`MoveTaskResponse` 包含 `task: Task`、`movedCount: number`、`keyMap: Record<string, string>`，便于列表场景批量更新本地状态。

### 国际化（`src/i18n/messages/zh-CN.ts` / `en.ts`）
新增按钮、对话框文案、成功/失败提示、策略说明。

## 六、边界 / 异常

| 场景 | 处理 |
| --- | --- |
| 目标项目 = 源项目 | 4xx，前端禁用同项目选项 |
| 操作者不在 B 项目 | 4xx，前端不展示该项目 |
| 任务有子任务但 `includeDescendants=false` | 4xx，提示用户开启或改为搬整棵树 |
| 任务 `parent_id` 在外部（被迁任务作为某个非迁任务的子）| 自动 `parent_id=null`，活动日志记录解除关系 |
| 序号预留并发 | 复用 `TaskSequenceService` 现有 `SELECT … FOR UPDATE` 路径，安全 |
| Label 名在 B 已存在但大小写不同 | 按现有 `LabelService` 唯一性策略处理（保持大小写一致） |
| 旧 URL 访问 | 通过 `task_key_aliases` 兜底；命中 alias 时响应附 `redirectedFrom` |
| 收藏 | `task_favorites` 不动，用户的"收藏"列表跨项目自然显示新 key |
| `taskKey` 已被外部系统引用（机器人/邮件链接）| alias 表覆盖一段时间；活动日志保留新旧 key 关系 |

## 七、迭代分期

| 阶段 | 范围 |
| --- | --- |
| **v1（本期）** | 单任务移动（含子树）、label remap/drop、assignee 三策略、alias 兜底、单事务、活动日志 |
| **v1.1** | 批量任务移动（多根）、前端批量结果汇总 |
| **v2** | "复制到项目"语义（保留原任务）、可选保留原 key（仅当 B 项目尚无该 key 时） |
| **v2.1** | 移动时附带选择是否搬运评论 / 是否清空进度 |

## 八、测试要点

后端单测（建议 `TaskMoveServiceTest`）：
- 子树整体迁移、key 全部更换、序号连续；
- label remap 命中已有 / 自动创建 / drop；
- assignee 三种策略；
- 不允许同项目、未授权双边、`includeDescendants=false` 但有子；
- alias 表写入 + `getByKeyOrThrow` 命中兜底；
- 并发：两个迁移同时进入同一目标项目，序号无重复（依赖现有 `FOR UPDATE`）。

前端：
- `MoveTaskModal` 表单与 API 契约；
- 移动成功后路由 `replace` 到新 key；
- `TaskListView` 批量选择移动后本地状态用 `keyMap` 更新。

## 九、向后兼容 / 数据迁移

- 仅新增表，无破坏性 schema 变更。
- `getByKeyOrThrow` 行为变更：未命中主表时改查 alias，向后兼容（旧链接更鲁棒，新行为对未迁移任务无影响）。
- 部署后无需脚本回填。
