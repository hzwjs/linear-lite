# 任务评论、@ 提及与站内通知 实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 实现任务详情页富文本评论（复用 `TiptapEditor` 管线）、项目成员 `@`、站内通知列表与未读角标、SSE 近实时推送；删除仅作者且发布后 3 分钟内有效。

**方案概述：** 后端新增 `task_comments`、`comment_mentions`、`in_app_notifications` 三张表（无外键）；`POST` 评论在单事务内写评论、提及与通知；`DELETE` 校验作者与 3 分钟窗口；`ProjectService` 批量删任务时级联清理评论与通知；前端扩展 TipTap Mention、任务详情接真实评论区、布局增加通知中心与 `EventSource`。

**技术栈：** Spring Boot、MyBatis-Plus、Vue 3、Pinia、Axios、TipTap、Vitest、JUnit 5。

**设计依据：** `docs/plans/2026-04-09-communication-comments-notifications-design.md`

---

### 任务 1：数据库表与实体

**涉及文件：**
- 修改：`linear-lite-server/src/main/resources/schema.sql`
- 新增：`linear-lite-server/src/main/resources/schema-vXX-task-comments-notifications.sql`（版本号按仓库现有递增）
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/entity/TaskComment.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/entity/CommentMention.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/entity/InAppNotification.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskCommentMapper.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/mapper/CommentMentionMapper.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/mapper/InAppNotificationMapper.java`

**步骤 1：编写失败测试**

新建 `TaskCommentServiceTest`（或 Mapper 集成测试）：插入评论并查询 — 在实体/Mapper 未实现前应编译失败或运行失败。

**步骤 2：运行测试确认失败**

运行：`cd linear-lite-server && mvn -q -Dtest=TaskCommentServiceTest test`

预期：FAIL（类或表不存在）。

**步骤 3：最小实现**

- 在 `schema.sql` 与增量 SQL 中定义三表与索引（无 `FOREIGN KEY`）
- 添加 Entity + Mapper 接口（MyBatis-Plus `BaseMapper`）

**步骤 4：运行测试确认通过**

运行：同上

预期：至少 Mapper 级插入/查询通过；若无 H2 集成环境，可改为 `@SpringBootTest` + Testcontainers 或与现有测试风格一致。

**步骤 5：提交**

```bash
git add linear-lite-server/src/main/resources/schema.sql linear-lite-server/src/main/resources/schema-vXX-task-comments-notifications.sql linear-lite-server/src/main/java/com/linearlite/server/entity/TaskComment.java linear-lite-server/src/main/java/com/linearlite/server/entity/CommentMention.java linear-lite-server/src/main/java/com/linearlite/server/entity/InAppNotification.java linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskCommentMapper.java linear-lite-server/src/main/java/com/linearlite/server/mapper/CommentMentionMapper.java linear-lite-server/src/main/java/com/linearlite/server/mapper/InAppNotificationMapper.java linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommentServiceTest.java
git commit -m "feat(comments): add schema and entities for comments and notifications"
```

---

### 任务 2：评论服务与 API（含权限、提及校验、3 分钟删除）

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommentService.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/ProjectAccessService.java` 中复用成员校验方法（若已有则只调用）
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/TaskCommentResponse.java`、`CreateTaskCommentRequest.java`
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`（或新建 `TaskCommentController` 映射 `/api/tasks/{taskKey}/comments`）
- 新增：`linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommentServiceTest.java`（扩充）

**步骤 1：编写失败测试**

覆盖：

- 项目成员 `POST` 成功
- 非成员 `POST` → 403
- `body` 中解析出的 `mentionedUserIds` 含非成员 → 422
- 作者 3 分钟内 `DELETE` 成功
- 同一评论 3 分钟后 `DELETE` → 409
- 非作者 `DELETE` → 403

**步骤 2：运行测试确认失败**

运行：`cd linear-lite-server && mvn -q -Dtest=TaskCommentServiceTest test`

预期：FAIL

**步骤 3：最小实现**

- `list` / `create` / `delete`：`create` 内解析请求体中的提及 id 列表（与前端约定：请求 DTO 带 `body` + `mentionedUserIds`，**服务端对 `mentionedUserIds` 与正文内 Mention 节点双校验**，防止只传列表不改正文）
- 事务：`insert comment` → `batch insert mentions` → `insert notifications`（类型 `mention`，`summary` 可从纯文本截断生成）
- 删除：校验 `author_id` 与 `Duration.between(createdAt, now()).toMinutes() < 3`（边界：恰 180 秒用 `ChronoUnit.SECONDS` 更稳）

**步骤 4：运行测试确认通过**

运行：同上

预期：PASS

**步骤 5：提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommentService.java linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java linear-lite-server/src/main/java/com/linearlite/server/dto/
git commit -m "feat(comments): CRUD API with mention validation and 3m delete"
```

（路径按实际新增文件调整 `git add`。）

---

### 任务 3：通知查询与已读 API

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/InAppNotificationService.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/controller/NotificationController.java`（`/api/me/notifications`）
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/InAppNotificationResponse.java`
- 新增：`linear-lite-server/src/test/java/com/linearlite/server/service/InAppNotificationServiceTest.java`

**步骤 1：编写失败测试**

- 用户 A 收不到用户 B 的通知列表
- `read` 幂等；`unread-count` 正确

**步骤 2：运行测试确认失败**

运行：`cd linear-lite-server && mvn -q -Dtest=InAppNotificationServiceTest test`

**步骤 3：最小实现**

- 分页/cursor 按 `id` 或 `created_at`；`read_at` 更新

**步骤 4：运行测试确认通过**

**步骤 5：提交**

```bash
git commit -m "feat(notifications): list, read, unread count API"
```

---

### 任务 4：SSE 推送与删任务级联

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/NotificationSseBroadcaster.java`（维护 `userId -> List<SseEmitter>`）
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/controller/NotificationSseController.java`
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/filter/JwtAuthFilter.java`（允许 `GET .../notifications/stream` 带鉴权）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommentService.java`（创建通知后调用 broadcaster）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`（删除项目下任务时删除相关 `task_comments`、`comment_mentions`、`in_app_notifications`）

**步骤 1：手动或集成测试说明**

- 启动服务，登录后连接 SSE，再 `POST` 评论 `@` 自己，观察事件到达。

**步骤 2：实现**

- `SseEmitter` 超时与 `onCompletion` 清理注册
- `MediaType.TEXT_EVENT_STREAM`
- 删任务路径：在 `ProjectService` 删除 `tasks` 前按 `task_id` 批量删子表（应用层）

**步骤 3：提交**

```bash
git commit -m "feat(notifications): SSE stream and cascade delete with tasks"
```

---

### 任务 5：前端 API 层与类型

**涉及文件：**
- 修改：`src/services/api/types.ts`
- 新增：`src/services/api/taskComments.ts`
- 新增：`src/services/api/notifications.ts`

**步骤 1：Vitest（若已有 api 层测试）或类型检查**

运行：`npm run build` 或 `npm run test`（按项目脚本）

**步骤 2：实现**

- `listComments(taskKey)`、`createComment(taskKey, body)`、`deleteComment(taskKey, commentId)`
- `listNotifications`、`markRead`、`unreadCount`、`openNotificationStream()` 返回 `EventSource` 封装

**步骤 3：提交**

```bash
git commit -m "feat(comments): client API for comments and notifications"
```

---

### 任务 6：TipTap Mention 与评论编辑器

**涉及文件：**
- 修改：`src/components/TiptapEditor.vue` 或新增 `src/components/TaskCommentEditor.vue`
- 新增：`src/extensions/Mention.ts`（或 `@tiptap/extension-mention` 配置）
- 修改：`package.json`（若新增 `@tiptap/extension-mention`）

**步骤 1：组件级手动验证清单**

- `@` 弹出成员列表；选择后序列化到 Markdown/HTML 中带有可解析的 `data-user-id` 或约定语法；`createComment` 提交 `body` + `mentionedUserIds`

**步骤 2：实现**

- `mentionedUserIds` 从编辑器 doc 遍历 `mention` 节点收集，与 `TaskCommentService` 校验一致

**步骤 3：提交**

```bash
git commit -m "feat(comments): TipTap mention in comment editor"
```

---

### 任务 7：TaskEditor 接入评论列表与发送

**涉及文件：**
- 修改：`src/components/TaskEditor.vue`
- 修改：`src/locales/*.json`（文案）

**步骤 1：手动验证**

打开任务详情：加载评论、发送、删除（3 分钟内）、非作者无删除按钮

**步骤 2：实现**

- 移除只读 `input`；`deletable` 驱动按钮；失败 toast

**步骤 3：提交**

```bash
git commit -m "feat(comments): wire TaskEditor comment thread"
```

---

### 任务 8：通知中心 UI 与全局 SSE

**涉及文件：**
- 修改：`src/App.vue` 或主导航布局组件（如 `src/views/MainLayout.vue` 若存在）
- 新增：`src/components/NotificationCenter.vue`
- 新增：`src/store/notificationStore.ts`（可选）

**步骤 1：手动验证**

角标、下拉列表、标记已读、点击跳转任务（`taskKey` 路由与现有一致）

**步骤 2：实现**

- 应用挂载时 `EventSource`（或登录后）；组件卸载 `close()`
- 与现有路由 `router.push` 打开对应任务

**步骤 3：提交**

```bash
git commit -m "feat(notifications): inbox UI and SSE subscription"
```

---

## 验证命令（全量）

```bash
cd linear-lite-server && mvn -q test
npm run test
npm run build
```

---

## 执行交接

计划已保存到 `docs/plans/2026-04-09-communication-comments-notifications.md`。执行路径（二选一）：

**1. 子代理驱动（当前会话）** — 按任务分派 subagent，任务间 review。需配合 `superpowers:subagent-driven-development`。

**2. 并行会话（独立执行）** — 在 worktree 中新开会话使用 `superpowers:executing-plans` 按批次执行并在检查点汇报。
