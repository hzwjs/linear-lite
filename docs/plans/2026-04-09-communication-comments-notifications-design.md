# 任务评论、@ 提及与站内通知 — 设计定稿

**目标：** 在任务详情页提供与任务描述一致的富文本评论；支持 `@` 项目成员；通过全局通知中心与 SSE 近实时送达；删除仅作者且在发布后 3 分钟内有效。

**设计日期：** 2026-04-09

**约束：** 数据库不使用外键；表间关联用逻辑 `*_id`，一致性由应用层维护（见仓库 `no-foreign-keys` 规则）。

---

## 1. 范围与产品行为

| 项 | 决策 |
|----|------|
| 评论挂载对象 | 仅任务（Issue）详情页，与现有「活动」同页；UI 可分「活动 / 评论」或时间线混排（实现阶段再定） |
| 通知入口 | 顶栏或侧栏 **统一通知中心**（列表、单条/全部已读、未读角标） |
| 评论正文 | **富文本**，与任务描述 **同一存储与校验管线**（前端当前为 `TiptapEditor` → Markdown 字符串经 API 提交；后端 `TEXT`） |
| `@` 候选 | **仅该任务所属项目的成员**（与 `project_members` 一致）；服务端对解析出的用户 id **二次校验** |
| 删除 | **仅作者**；**创建时间超过 3 分钟** 后禁止删除（以服务端 `created_at` 为准，返回 **409**） |
| 实时 | **SSE** 推送通知增量（单向、与「只收通知」匹配）；若未来要强协同再评估 WebSocket |

---

## 2. 数据模型（方案：独立评论表 + 提及表 + 通知表）

不使用 `FOREIGN KEY` / `REFERENCES`。

### 2.1 `task_comments`

- `id` BIGINT PK
- `task_id` BIGINT NOT NULL（逻辑关联 `tasks.id`）
- `author_id` BIGINT NOT NULL（逻辑关联 `users.id`）
- `body` TEXT NOT NULL（与 `tasks.description` 同格式，如 Markdown）
- `created_at` DATETIME NOT NULL

索引：`idx_task_comments_task_id (task_id, created_at)`（或含 `id` 保证排序稳定）。

### 2.2 `comment_mentions`

- `id` BIGINT PK
- `comment_id` BIGINT NOT NULL
- `mentioned_user_id` BIGINT NOT NULL

索引：`idx_comment_mentions_comment_id`、`idx_comment_mentions_user_id`（便于按用户查历史，可选）。

### 2.3 `in_app_notifications`

- `id` BIGINT PK
- `user_id` BIGINT NOT NULL（接收人）
- `type` VARCHAR(32) NOT NULL（如 `mention`）
- `task_id` BIGINT NOT NULL
- `comment_id` BIGINT NOT NULL
- `summary` VARCHAR(512) NULL（纯文本摘要，供列表展示）
- `read_at` DATETIME NULL
- `created_at` DATETIME NOT NULL

索引：`idx_in_app_notifications_user_read (user_id, read_at, created_at)` 等，按查询模式微调。

### 2.4 任务删除

任务被删除时，由 **应用层** 删除或归档其评论、提及行与关联通知（不做 DB 级 CASCADE）。

### 2.5 评论删除

采用 **硬删** `task_comments`（或软删 `deleted_at`，实现计划中选一种并写清）。关联的 `in_app_notifications` **标记失效或删除**，避免通知中心点开指向不存在内容；若采用软删评论，则通知可展示「评论已删除」。

---

## 3. API 草案

### 3.1 评论

- `GET /tasks/{taskKey}/comments`：分页；项含 `id`、`author`、`body`、`createdAt`、`deletable`（作者且未超 3 分钟）
- `POST /tasks/{taskKey}/comments`：body 与任务描述字段一致；事务内写评论、提及、通知
- `DELETE /tasks/{taskKey}/comments/{commentId}`：403 非作者；**409** 超时；404 任务/评论不匹配

### 3.2 通知

- `GET /me/notifications?cursor=&limit=&unreadOnly=`
- `POST /me/notifications/{id}/read`、`POST /me/notifications/read-all`（按需）
- `GET /me/notifications/unread-count`

### 3.3 @ 数据源

复用已有「项目成员」接口；若无则增加 `GET /tasks/{taskKey}/mention-candidates` 返回当前用户可见的项目成员精简列表。

### 3.4 SSE

- `GET /me/notifications/stream`（路径前缀与现有 `/api` 约定对齐）
- 鉴权与现有 JWT/会话一致（需确认 `JwtAuthFilter` 对长连接与 `text/event-stream` 的处理）
- 事件载荷小：`notificationId`、`type`、`taskKey`、`commentId`、`createdAt`；客户端可再拉详情或合并列表

---

## 4. 错误语义（HTTP）

| 场景 | 码 |
|------|-----|
| taskKey 不存在 | 404 |
| 非项目成员读/发 | 403 |
| 正文无法解析/校验失败 | 400 |
| `@` 指向非项目成员 | 422 |
| 删他人评论 | 403 |
| 删自己的评论超过 3 分钟 | **409** |
| comment 不存在或不属于该任务 | 404 |
| 标记他人通知已读 | 403 |

与现有统一错误体风格对齐。

---

## 5. 并发与一致性

- `POST` 评论：`task_comments` → `comment_mentions` → `in_app_notifications` **同一事务**
- 列表排序：`created_at` + `id` 稳定次序
- SSE：多标签页多连接；客户端按 `notificationId` 去重

---

## 6. 前端要点

- **富文本复用**：以 `TiptapEditor` 为基，抽 **可配置扩展集**（评论区可 **精简工具栏**）；新增 **Mention** 节点（`@` 弹层数据来自项目成员），**从文档结构提取 `userId`** 写入 `comment_mentions`，不全靠正则解析 HTML/Markdown
- **TaskEditor**：替换只读评论占位为真实编辑器 + 列表；打开任务时拉评论
- **通知中心**：新组件 + Pinia 或组合式 store；`EventSource` 订阅 SSE，收到事件后刷新未读或增量合并
- **i18n**：新增文案键

---

## 7. 测试要点

- 后端：成员成功发评；非成员 403；非法 mention 422；@ 多人各一条通知；已读幂等；删除 3 分钟边界（分钟内成功、分钟外 409）
- SSE：可后置集成测试或手工验证
- 前端：关键 API 映射与 Mention 解析单测（若有 Vitest 基础设施）

---

## 8. 已排除 / YAGNI

- 跨项目 `@`、全站收件人不限成员
- 评论可编辑（常规编辑）；仅删除 + 3 分钟窗口
- 邮件/推送通知
- 首轮非必须：评论内图片上传与描述完全一致（可按描述现有能力渐进对齐）

---

**相关实现计划：** `docs/plans/2026-04-09-communication-comments-notifications.md`
