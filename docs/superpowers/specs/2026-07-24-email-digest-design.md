# 项目邮件通知设计

**目标：** 为项目增加邮件通知能力，先实现 `今日汇总`，并提供项目维度、场景维度的发送开关；邮件在服务器时区每日 `16:30` 发送，内容采用专业 HTML 模板并保留纯文本备用。

**范围：** 后端通知作业、项目邮件配置、邮件模板与发送、前端项目设置入口、自动化测试。

## 当前状态

- 现有 `EmailService` 只负责注册验证码和项目邀请，发送的是简单文本邮件。
- 任务模型已经有 `dueDate`、`status`、`assigneeId`、`completedAt`。
- 项目设置面板已经存在，且已经有创始人专属配置区。
- 项目列表、任务详情和路由都已经具备可跳转链接。

## 设计结论

采用一条固定链路：

1. 项目级邮件配置表控制某个项目的某个场景是否启用。
2. 每日 `16:30` 的调度器只扫描启用项目。
3. 汇总按 `项目 + 负责人` 分组，只发送给有邮箱的任务负责人。
4. 邮件由独立的通知邮件服务生成 HTML + 纯文本双格式内容。
5. 发送记录落库，保证同一 `项目 + 场景 + 日期 + 收件人` 只成功发送一次。

当前阶段只实现一个场景键：`daily_summary`。

## 数据模型

### `project_email_preferences`

用途：保存项目维度的场景开关。

字段：

- `id` BIGINT PK
- `project_id` BIGINT NOT NULL
- `scenario_key` VARCHAR(32) NOT NULL
- `enabled` TINYINT(1) NOT NULL
- `created_at` DATETIME NOT NULL
- `updated_at` DATETIME NOT NULL

约束：

- `UNIQUE(project_id, scenario_key)`
- 不使用外键
- 读取时只认这一张表，不做缺省兜底

规则：

- 新建项目时为 `daily_summary` 初始化一条记录，默认 `enabled = false`
- 既有项目通过一次性迁移回填
- 后续新增场景也沿用同一张表

### `project_email_dispatches`

用途：记录每次汇总邮件的发送状态，保证幂等和可追踪。

字段：

- `id` BIGINT PK
- `project_id` BIGINT NOT NULL
- `scenario_key` VARCHAR(32) NOT NULL
- `business_date` DATE NOT NULL
- `recipient_user_id` BIGINT NOT NULL
- `status` VARCHAR(16) NOT NULL
- `subject` VARCHAR(255) NOT NULL
- `task_count` INT NOT NULL
- `last_error` VARCHAR(1024) DEFAULT NULL
- `sent_at` DATETIME DEFAULT NULL
- `created_at` DATETIME NOT NULL
- `updated_at` DATETIME NOT NULL

约束：

- `UNIQUE(project_id, scenario_key, business_date, recipient_user_id)`
- `status` 取值：`pending`、`sent`、`failed`

规则：

- 先占位再发送，发送成功后标记 `sent`
- 失败后保留 `failed` 和错误信息，便于重试和排查
- 同一条记录再次执行时只允许从未发送状态进入发送流程

## 后端组件

### `ProjectEmailPreferenceService`

职责：

- 读取项目邮件开关
- 保存项目邮件开关
- 在项目创建时初始化默认记录
- 在既有项目迁移时回填默认记录

### `DailySummaryScheduler`

职责：

- 通过 `@Scheduled(cron = "0 30 16 * * *")` 在每天 `16:30` 触发
- 使用服务器默认时区
- 触发后调用汇总服务

### `DailySummaryService`

职责：

- 读取当天启用 `daily_summary` 的项目
- 按项目查找满足条件的任务
- 按任务负责人分组生成收件人列表
- 调用邮件编排器和发送器
- 写入发送记录

汇总口径：

- 只统计未完成任务
- 终态任务不进入邮件：`done`、`canceled`、`duplicate`
- 包含两类任务：
  - 截止日是今天的任务
  - 截止日早于当前时间且尚未完成的任务

分组口径：

- 以 `assigneeId` 为收件人归属
- 只发送给 `users.email` 存在且有效的用户
- 无负责人任务不发送
- 同一用户在多个项目中有任务时，按项目分别发送

### `DigestMailComposer`

职责：

- 将任务数据渲染成专业邮件内容
- 生成 `subject`、`htmlBody`、`textBody`
- 对任务标题、项目名等动态字段做 HTML 转义
- 生成绝对链接，使用 `app.public-base-url`

邮件形式：

- 顶部品牌区
- 日期与摘要数字
- `今日到期` 与 `已逾期` 两个区块
- 每条任务展示：任务键、标题、截止时间、状态
- 主按钮链接到站点首页 `/`
- 每条任务链接到任务详情页 `/projects/{projectId}/tasks/{taskKey}`
- 同时发送纯文本备用内容

### `DigestMailSender`

职责：

- 基于 `JavaMailSender` 发送 `MimeMessage`
- 使用 `MimeMessageHelper` 构造 `multipart/alternative`
- 发送失败时抛出明确异常，由调度服务记录到发送表

### `DailySummaryDispatchService`

职责：

- 负责获取或创建发送记录
- 在单条记录上执行状态流转
- 处理重试、失败信息和发送时间
- 保证同一收件人同一天只成功发送一次

## API 设计

### 项目邮件配置

`GET /api/projects/{id}/email-settings`

返回：

- `scenarioKey`
- `enabled`

`PUT /api/projects/{id}/email-settings`

请求体：

- `items: [{ scenarioKey, enabled }]`

规则：

- 只允许项目创建者修改
- `scenarioKey` 只接受已定义场景
- 当前阶段只开放 `daily_summary`

## 前端入口

项目设置弹窗增加「邮件通知」区域：

- 当前阶段展示一个开关：`今日汇总`
- 仅项目创建者可编辑
- 保存后立即回写后端
- 非创建者不显示编辑控件

## 配置项

新增应用配置：

- `app.public-base-url`：邮件中的绝对跳转前缀
- `app.mail.from-name`：发件人显示名

邮件地址仍沿用现有 SMTP 配置，不新增独立发送通道。

## 错误处理

- 项目邮件配置不存在：视为迁移失败，不做默认兜底
- 非创建者修改配置：403
- 发送器失败：记录到 dispatch 表并继续处理其他收件人
- 某个项目当天没有符合条件的任务：不发邮件，不创建发送记录
- 某个用户没有邮箱：跳过该用户，不影响同项目其他收件人

## 测试

### 后端

- 项目创建时自动初始化 `daily_summary` 配置
- 既有项目回填配置成功
- 项目开关关闭时不进入调度候选
- 项目开关开启时可进入调度候选
- 同一收件人同一天只会产生一条成功发送记录
- 邮件模板包含品牌、摘要、任务链接与绝对 URL
- 任务筛选排除终态任务

### 前端

- 项目设置弹窗能读取并保存邮件开关
- 仅创建者可编辑开关
- 保存失败时显示错误

## 非目标

- 不做用户级邮件订阅
- 不做截止期临近场景
- 不做邮件退订中心
- 不做富媒体邮件或图片广告式排版
- 不做历史邮件中心
