# 全系统北京时间统一改造计划

## 1. 改造目标

Linear Lite 当前所有用户均在中国，系统统一使用 `Asia/Shanghai` 作为唯一业务时区。

改造完成后：

- 系统产生的绝对时间统一按北京时间记录和展示。
- API 的绝对时间统一返回带 `+08:00` 的 ISO 8601 字符串。
- 截止日期、计划开始日期、邮件业务日等日历字段保持日期语义，不参与时区换算。
- 数据库、JVM、定时任务、统计口径和前端展示不再依赖机器默认时区。
- 历史数据与新数据采用同一条时间路径，不保留双读、兼容字段或兜底解析。

## 2. 时间语义规则

### 2.1 绝对时间

适用于“某件事在什么时候发生”的字段：

- `created_at`、`updated_at`、`archived_at`
- `completed_at`、`read_at`、`sent_at`
- 评论、活动、通知、附件、收藏、修订版时间
- Agent Session、Job 的创建、开始、完成、租约和重试时间
- 验证码过期、使用时间
- 语义索引任务的 `run_after`、`lease_until`

统一规则：

- 数据库：`DATETIME(3)`，值按 `Asia/Shanghai` 写入。
- Java：绝对时间使用 `OffsetDateTime` 或 `Instant`；数据库适配时明确绑定 `Asia/Shanghai`。
- API：输出 `2026-08-20T13:55:48.123+08:00`。
- 前端：只通过统一时间工具解析和格式化，展示时固定使用 `Asia/Shanghai`。

### 2.2 日历日期

适用于“哪一天”的字段：

- `tasks.due_date`
- `tasks.planned_start_date`
- `project_email_dispatches.business_date`

统一规则：

- 数据库：`DATE`。
- Java：`LocalDate`。
- API：`YYYY-MM-DD`。
- 前端：以日期字符串和日期选择器处理，不转换成毫秒时间戳。

### 2.3 本地业务时间

每日摘要、周/月统计、逾期判断等需要本地日期边界的逻辑，统一使用：

```text
ZoneId.of("Asia/Shanghai")
```

禁止使用 `ZoneId.systemDefault()`、服务器系统时区或浏览器默认时区作为业务依据。

## 3. 改造范围

### 3.1 数据库

线上实际发现 51 个时间字段，其中 50 个为 `DATETIME`、1 个为 `DATE`。涉及：

- 用户、项目、项目成员、邀请
- Task、Task Comment、Task Activity、Task Attachment、Favorite
- Project Document、Document Revision、Document Attachment、Favorite
- In-App Notification
- Agent Session、Agent Job、Bridge Credential
- Semantic Index Job
- 邮件验证码、邮件偏好、邮件发送记录
- GitHub/GitLab 仓库配置

需要调整：

1. 所有绝对时间列统一为 `DATETIME(3)`。
2. `due_date`、`planned_start_date` 从 `DATETIME` 改为 `DATE`。
3. 保留现有索引和唯一约束，仅调整受影响列类型。
4. 在数据库连接初始化时固定会话时区为 `+08:00`。
5. `schema.sql` 和所有历史增量脚本的最终结构保持一致，增量脚本最终归档回 `schema.sql`。

主要文件：

- `linear-lite-server/src/main/resources/schema.sql`
- `linear-lite-server/src/main/resources/migration-*.sql`
- `linear-lite-server/src/main/resources/schema-v*.sql`

### 3.2 历史数据

当前线上 MySQL 系统时区为 UTC，文档 519 的 `updated_at` 已确认比北京时间慢 8 小时。

迁移规则：

- 绝对时间字段：统一执行 `+8 小时`，转换为北京时间值。
- 日期字段：只保留原日期部分，不执行小时偏移。
- 已经由应用按北京时间写入的记录不得重复加 8 小时；迁移前必须根据字段来源和部署时间划分数据范围。
- 迁移脚本执行前生成受影响表、字段、行数和最早/最晚时间的审计结果。

历史时间修正必须是一次性数据操作，完成后删除兼容分支，不在代码中根据记录来源猜测时区。

### 3.3 后端实体和 DTO

当前约 78 个 Java 文件使用 `LocalDateTime`，需要按语义改造：

- Entity：绝对时间字段改为带明确时区语义的类型。
- DTO：绝对时间统一输出带 `+08:00` 的格式；日期字段改为 `LocalDate`。
- Request：Task 日期请求只接受 `YYYY-MM-DD`；绝对时间请求只接受带偏移量的 ISO 8601。
- Mapper：移除依赖数据库隐式时区的时间映射。
- Service：将 `LocalDateTime.now()` 替换为统一时间入口；本地日期计算显式使用 `Asia/Shanghai`。

重点模块：

- Task、TaskCommandService、TaskStatusService、TaskActivityService
- Project Document、ProjectDocumentCommandService、ProjectDocumentQueryService
- Task Comment、Notification
- AgentTaskOrchestrationService、AgentSessionStreamService
- ProjectContentSemanticIndexQueueService
- AuthService
- DailySummaryScheduler、DailySummaryDispatchService、AnalyticsService
- GitHub/GitLab Webhook 服务

### 3.4 后端统一时间模块

新增一个集中时间模块，作为全系统唯一时间入口，负责：

- 获取当前北京时间
- 绝对时间与数据库值互转
- API 时间序列化和反序列化
- 北京时间的日期、周、月边界计算
- 统一的过期、逾期和租约判断

所有业务模块不得自行调用 `LocalDateTime.now()`、`Instant.now()`、`ZoneId.systemDefault()` 或直接拼接时间字符串。

### 3.5 数据库写入路径

统一检查并改造以下写入方式：

- `DEFAULT CURRENT_TIMESTAMP`
- `ON UPDATE CURRENT_TIMESTAMP`
- SQL 中的 `CURRENT_TIMESTAMP`
- Java 中的 `LocalDateTime.now()`
- Java 中的 `LocalDateTime.now().plus...`
- 直接依赖 MySQL 当前会话时间的查询条件

所有绝对时间写入必须经过固定的北京时间时间源；队列、租约、验证码和过期判断必须使用同一时间基准。

### 3.6 前端 API 和类型

前端目前在多个组件中直接调用 `new Date(string)`，需要收敛到 API 层：

- 绝对时间：API 层解析为统一的毫秒时间戳或统一时间对象。
- 日历日期：保留 `YYYY-MM-DD` 字符串。
- 禁止把 `dueDate`、`plannedStartDate` 转成时间戳后再传回服务端。
- 删除无时区字符串的隐式解析。
- 所有格式化统一指定 `timeZone: 'Asia/Shanghai'`。

重点文件：

- `src/services/api/task.ts`
- `src/services/api/documents.ts`
- `src/services/api/activity.ts`
- `src/services/api/notifications.ts`
- `src/utils/taskDate.ts`
- `src/components/documents/DocumentEditor.vue`
- `src/components/documents/DocumentHistoryPanel.vue`
- `src/components/comments/CommentThreadList.vue`
- `src/components/TaskEditor.vue`
- `src/components/TaskCard.vue`
- `src/components/TaskListView.vue`
- `src/views/AnalyticsView.vue`
- `src/mobile/`

### 3.7 定时任务、统计和外部接口

- 每日摘要的 cron 和业务日期统一按 `Asia/Shanghai`。
- Analytics 的周、月、日桶统一按北京时间切分。
- Task 逾期判断统一按北京时间当天边界计算。
- Agent Job 的租约和重试时间使用绝对时间，不使用本地日期计算。
- GitHub、GitLab、Pi Bridge 的外部时间先转换为北京时间内部标准，再进入业务模型。
- Pi Bridge 已使用带 `Z` 的 ISO 时间，需要在服务端入口处统一转换，不允许直接混用无时区字符串。

## 4. 实施顺序

### 阶段一：冻结时间契约

确定所有字段属于“绝对时间”或“日历日期”，建立字段清单并禁止新增未分类时间字段。

产出：

- 数据库时间字段清单
- Java Entity/DTO 时间类型清单
- API 时间格式清单
- 前端时间展示入口清单

### 阶段二：先完成基础设施

先落地：

- MySQL 会话时区 `+08:00`
- JVM 默认时区 `Asia/Shanghai`
- Jackson 时间序列化规范
- 后端统一时间模块
- 前端统一时间工具

基础设施完成后，业务模块只能通过统一入口处理时间。

### 阶段三：改造数据模型和数据库

- 修改 `schema.sql`。
- 编写一次性线上数据修正脚本。
- 将 Task 日期字段改为 `DATE`。
- 将绝对时间字段统一为 `DATETIME(3)`。
- 执行线上数据修正。
- 将最终结构回写到 `schema.sql`。

### 阶段四：改造后端业务链路

按以下顺序改造：

1. Task 和 Task Comment
2. Project Document 和 Revision
3. Notification
4. Agent Job、Session、Semantic Index
5. Email、Auth、Project、Repository、Attachment
6. Analytics、Webhook、定时任务

### 阶段五：改造前端消费链路

按 API 层、桌面端、移动端的顺序统一类型和格式化入口，删除组件内的隐式时间解析。

### 阶段六：清理旧路径

完成改造后删除：

- 无时区时间字符串接口
- 直接使用浏览器默认时区的展示逻辑
- 直接使用服务器默认时区的业务逻辑
- `LocalDateTime` 表示绝对时间的字段
- 双格式解析和历史字段兼容分支

## 5. 成本评估

| 工作项 | 预计人日 |
|---|---:|
| 时间字段和接口契约冻结 | 1–2 |
| 后端时间基础设施 | 1–2 |
| 数据库结构与历史数据处理 | 2–3 |
| 后端业务模块改造 | 4–6 |
| 前端桌面端和移动端改造 | 3–4 |
| 定时任务、统计、Agent、Webhook | 2–3 |
| 合计 | 13–20 |

按单人连续开发计算约 3–4 周；如果后端、前端和数据库并行，日历周期约 2 周。

## 6. 最终方案

采用“北京时间作为唯一业务时区”的统一方案：

- 绝对时间按北京时间记录和传输。
- 日历日期保持纯日期类型。
- 所有计算显式使用 `Asia/Shanghai`。
- 数据库、后端、API、前端和定时任务使用同一时间契约。
- 历史 UTC 绝对时间一次性转换为北京时间。
- 不保留兼容字段、双读双写或 fallback 解析。

该方案适合当前所有用户位于中国的产品范围，能解决文档、任务、评论、通知、Agent、统计和邮件等全系统时间不一致问题；未来若支持海外用户，再将展示时区从固定 `Asia/Shanghai` 抽离为用户或项目配置。
