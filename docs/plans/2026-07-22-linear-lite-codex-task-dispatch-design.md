# Linear Lite 任务派发至 Codex 执行设计方案

- 日期：2026-07-22
- 状态：Draft（已明确 Runner 部署边界）
- 涉及模块：Linear Lite 前端、`linear-lite-server`、新增 `linear-lite-codex-runner`、本机 Codex App Server

## 一、背景

Linear Lite 与 Codex 的集成需求分为两个方向：

1. 在 Linear Lite 中创建任务，并将该任务交给 Codex 执行；
2. 将 Codex 中的执行过程识别并记录到 Linear Lite。

本方案只覆盖方向 1。

方向 1 的核心条件已经具备：用户从 Linear Lite 的某个确定任务发起执行，因此系统在派发前已经持有唯一 `taskKey`，不需要由 Codex 对自然语言进行任务搜索或语义匹配。

官方 Codex for Linear 采用相同的身份绑定模式：用户在具体 Linear issue 上指派 Codex 或通过评论触发 Codex，issue 上下文随触发事件进入 Codex，后续执行始终绑定原 issue。Linear Lite 复用该模式，但执行入口使用本机 Codex App Server，不依赖 OpenAI 与 Linear 之间的专用云连接器。

## 二、目标

用户可以在 Linear Lite 任务详情中点击“交给 Codex”，系统将该任务的固定快照派发到已经绑定的代码仓库，由受控的本地 Codex Runner 在独立 Git worktree 中执行。

一次派发必须满足：

- 派发对象始终是路径中的唯一 `taskKey`；
- Codex thread 与 Linear Lite 任务、执行记录一一绑定；
- Linear Lite Server 不保存 Codex 登录凭据，不直接访问开发机文件系统；
- 每次执行使用独立 worktree，不污染用户当前工作区；
- Runner 中断后可以通过持久化的 `codexThreadId` 恢复同一执行；
- 同一任务同一时刻最多存在一个非终态 Codex 执行；
- 执行结果以结构化数据返回，可在 Linear Lite 中稳定展示；
- 用户可以在 Codex 请求补充信息时，从 Linear Lite 继续同一 thread。

## 三、非目标

本期不实现：

- Codex 主动搜索、创建或更新 Linear Lite 任务；
- Linear Lite MCP Server；
- 任务语义检索；
- 调用未公开的 Codex 云任务创建接口；
- 自动提交、推送分支或创建合并请求；
- Codex 自动修改任务状态、优先级、负责人、描述或评论；
- 保存模型推理内容、完整终端输出或环境变量；
- 将 Runner 或 Codex App 打包进现有单 JAR；
- 自动化点击或控制 Codex App 图形界面；
- 附件和任务描述图片作为 Codex 输入。

## 四、核心设计决策

### 4.1 使用本机 Codex App Server，不使用 MCP 或独立 SDK thread

MCP 解决的是“Codex 主动访问外部系统”的工具调用问题，而本需求的调用方向是“Linear Lite 主动创建一次 Codex 执行”。

执行入口固定为当前开发机的 `codex app-server` daemon。Runner 使用 App Server 协议创建和恢复 thread；Codex App 连接同一 daemon，因此任务执行以本机 Codex App 会话呈现，而不是一个不可见的后台 CLI 会话。

Runner 不自动化 App 的窗口、鼠标或键盘。它只通过 App Server 的本机受控传输创建线程、提交 turn、订阅事件和恢复 thread。App Server 支持能力与协议版本必须由 POC 验证后才进入正式替换，POC 的通过条件是：

- Runner 创建的 thread 在已打开的 Codex App 中可见；
- App 与 Runner 显示并使用同一个 thread ID；
- App Server 可固定 worktree 作为工作目录；
- 结构化最终结果、事件订阅和 `needs_input` 可由 Runner 稳定映射；
- App Server daemon 重启后可按原 thread ID 恢复。

POC 未通过时，集成不发布；不退回到直接 SDK/CLI thread 路径，因为“在 Codex App 中可见”是本需求的完成条件。

### 4.2 独立 Runner，不在 Spring Boot 内运行 Codex

新增独立 Node.js 进程 `linear-lite-codex-runner`。它固定运行在发起者的本机开发环境；不得部署到承载 Linear Lite Web、API 或 MySQL 的服务器上。

这里的“本机”是拥有目标代码仓库、Git 凭据和 Codex 登录态的开发者工作站。它不是一台共享的远程执行服务器，也不与 Linear Lite 的应用部署目录共用文件系统。

Runner 只通过出站 HTTPS 连接 Linear Lite Server：

- 定期上报存活状态和已注册仓库；
- 领取属于自己的待执行记录；
- 流式上报白名单事件；
- 获取用户补充消息；
- 上报最终结果。

Linear Lite Server 不反向访问 Runner，也不接受前端传入的绝对工作目录。

### 4.3 项目只绑定仓库身份，不保存绝对路径

Runner 使用本地配置维护唯一映射：

```text
repositoryKey: linear-lite
workingDirectory: /Users/.../linear-lite-1
```

Runner 向服务端注册：

- `repositoryKey`
- 展示名称
- 去除凭据后的远程仓库身份
- 默认分支

服务端只保存 `repositoryKey` 和仓库展示信息，不保存本地绝对路径。Runner 收到任务后，只能通过本地白名单将 `repositoryKey` 解析为工作目录；禁止把服务器返回值直接作为文件系统路径。

### 4.4 每次执行使用独立 worktree

Runner 在领取执行后执行固定流程：

1. 校验仓库在本地白名单中，并确认存在固定远程 `origin`；
2. `git fetch origin <baseBranch>`；
3. 基于 `origin/<baseBranch>` 创建专属分支；
4. 在 Runner 管理目录创建独立 worktree；
5. 将 worktree 作为 Codex App Server thread 的 `workingDirectory`。

分支命名固定为：

```text
codex/<task-key-lowercase>-<run-id-short>
```

例如：

```text
codex/eng-23-8f31a2c4
```

父仓库存在未提交修改时不影响创建和执行。`git fetch`、分支创建或 worktree 创建失败时，本次执行直接失败，不切换到父仓库或其他目录继续运行。

### 4.5 任务快照是本次执行的固定输入

派发事务读取任务与项目数据，生成不可变 `taskSnapshot`。快照至少包含：

- `taskKey`
- 数据库任务 ID
- 项目标识与项目名称
- 标题
- 描述
- 状态
- 优先级
- 标签
- 截止日期
- 计划开始日期
- 当前更新时间
- 用户在派发时填写的补充指令

Codex 执行期间任务被其他用户修改时，不替换本次快照。前端通过比较当前任务 `updatedAt` 与快照时间，提示“任务内容已在派发后发生变化”。

## 五、总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│ Linear Lite 前端                                              │
│ · 任务详情“交给 Codex”                                        │
│ · Runner/仓库绑定                                              │
│ · 执行状态、补充问题、结果展示                                  │
└──────────────────────────────┬───────────────────────────────┘
                               │ JWT / REST
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ linear-lite-server                                            │
│ · CodexRunnerService                                          │
│ · CodexProjectBindingService                                  │
│ · CodexRunService                                             │
│ · 任务快照、状态机、幂等、权限、租约                            │
└──────────────────────────────┬───────────────────────────────┘
                               │ Runner Token / 出站轮询
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ linear-lite-codex-runner                                      │
│ · repositoryKey → 本地路径白名单                               │
│ · Git fetch / branch / worktree                               │
│ · Codex App Server Client                                      │
│ · thread 启动、恢复、取消与事件订阅                            │
│ · 事件过滤与结果上报                                           │
└──────────────────────────────┬───────────────────────────────┘
                               │ workingDirectory
                               ▼
       独立 Git worktree + Codex App Server daemon + Codex App thread
```

### 5.1 部署边界与使用体验

系统固定分为两个部署面，职责不可混用：

| 部署面 | 部署位置 | 持有的数据/凭据 | 允许的行为 | 明确禁止 |
| --- | --- | --- | --- | --- |
| Linear Lite 服务端 | 公网服务器 | 数据库、用户 JWT、Runner Token 哈希、任务快照、执行状态 | 提供 Web/API、保存绑定、接收心跳和结果 | 保存 Codex 凭据、访问开发机文件、创建 Git worktree、直接调用 Codex |
| Codex Runner | 项目创建者的本机开发环境 | 本地 Git 仓库、Git 凭据、Codex CLI 登录态、Runner Token | 主动领取任务、创建本地 worktree、调用 Codex、上报事件 | 作为服务器守护进程运行、将仓库路径/凭据上报给服务端、执行其他用户 Runner 的任务 |

用户体验固定为：开发者在自己的电脑上常驻一个 Runner；在浏览器中的 Linear Lite 打开任务并点击“交给 Codex”后，该本机 Runner 在下一次轮询中领取任务并启动本机 Codex。浏览器不需要连接开发机，服务器也不需要对开发机开放入站端口。

服务器部署只包含 Linear Lite 单 JAR、其配置和数据库迁移。服务器不安装 Node.js、Codex CLI、Runner 配置或代码工作仓库；这些内容全部属于开发者本机。

### 5.2 本机首次接入流程

项目创建者在自己的开发机完成一次性配置：

1. 安装 Node.js 20+ 和 Codex CLI，并在**本机终端**完成 Codex 登录；
2. 确认目标代码仓库是可用的 Git clone，`origin` 与基础分支可访问；
3. 在 Linear Lite 项目设置创建一次性 Runner 连接码；
4. 在本机使用连接码注册 Runner，得到仅保存于本机的 Runner Token；
5. 写入本机 Runner 配置：云端 `serverUrl`、Runner Token、状态目录和仓库白名单；
6. 启动本机 Runner；它心跳上报仓库后，在项目设置中选择该 Runner、仓库和基础分支并保存绑定；
7. 从任务详情派发一条小范围任务，确认 worktree、Codex thread 和结果回传均发生在本机。

连接码、Runner Token 和 Codex 登录态均不得复制到服务器、浏览器 LocalStorage、项目仓库或聊天记录中。

## 六、领域模型

### 6.1 Codex Runner

代表一台已经连接 Linear Lite、具备 Codex 执行能力的机器。

关键属性：

- 归属用户；
- 独立认证 Token；
- 在线状态；
- 最后心跳时间；
- 已注册仓库集合；
- 可撤销。

### 6.2 Codex Repository

代表 Runner 白名单中的一个 Git 仓库。服务端只持有仓库身份，不持有真实路径。

### 6.3 Project Codex Binding

表示一个 Linear Lite 项目固定使用哪个 Runner、哪个仓库和哪个基础分支执行 Codex 任务。

一个项目最多存在一条有效绑定。

### 6.4 Codex Run

表示一次从具体任务发起的 Codex 执行。它绑定：

```text
taskKey + taskSnapshot + runnerId + repositoryId + codexThreadId
```

重新执行必须创建新的 `CodexRun`，不得复用已完成执行。

### 6.5 Codex Run Event

表示经过 Runner 过滤后、允许在 Linear Lite 中展示的执行事件。它不保存模型推理内容和完整命令输出。

### 6.6 Codex Run Message

表示用户针对 `needs_input` 执行提交的补充指令。每条消息只允许被 Runner 消费一次。

## 七、执行状态机

```text
queued
  └─ Runner 原子领取 → claimed
       └─ worktree 与 thread 就绪 → running
            ├─ Codex 请求补充信息 → needs_input
            │    └─ 用户回复并恢复 thread → running
            ├─ 正常完成 → completed
            ├─ 执行失败 → failed
            └─ 收到取消请求 → canceled
```

允许的状态转换固定为：

| 当前状态 | 下一状态 |
| --- | --- |
| `queued` | `claimed`、`canceled` |
| `claimed` | `running`、`failed`、`canceled` |
| `running` | `needs_input`、`completed`、`failed`、`canceled` |
| `needs_input` | `running`、`failed`、`canceled` |
| `completed` | 无 |
| `failed` | 无 |
| `canceled` | 无 |

服务端拒绝所有不在表内的状态转换。

## 八、数据模型

所有增量 DDL 最终归档到：

```text
linear-lite-server/src/main/resources/schema.sql
```

### 8.1 `codex_runners`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `user_id` | Runner 所有者 |
| `name` | 用户可识别名称 |
| `token_hash` | Runner Token 哈希，禁止保存明文 |
| `status` | `active/revoked` |
| `last_seen_at` | 最后心跳时间 |
| `created_at` | 创建时间 |
| `revoked_at` | 撤销时间 |

在线状态不单独落库，根据 `status=active` 且 `last_seen_at` 未超过心跳阈值唯一推导。

### 8.2 `codex_runner_enrollment_codes`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `user_id` | 创建连接码的用户 |
| `code_hash` | 一次性连接码哈希 |
| `expires_at` | 过期时间 |
| `consumed_at` | 消费时间 |
| `created_at` | 创建时间 |

规则：连接码十分钟后失效，只能消费一次。

### 8.3 `codex_repositories`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `runner_id` | 所属 Runner |
| `repository_key` | Runner 内唯一稳定键 |
| `display_name` | 展示名称 |
| `remote_identity` | 去除凭据后的远程仓库身份 |
| `default_branch` | Runner 上报的默认分支 |
| `last_seen_at` | 最后确认时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

唯一约束：`(runner_id, repository_key)`。

### 8.4 `project_codex_bindings`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `project_id` | Linear Lite 项目 |
| `runner_id` | 固定 Runner |
| `repository_id` | 固定仓库 |
| `base_branch` | 固定基础分支 |
| `created_by` | 配置人 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

唯一约束：`project_id`。

### 8.5 `codex_runs`

| 字段 | 说明 |
| --- | --- |
| `id` | UUID 字符串主键 |
| `client_request_id` | 前端派发幂等键 |
| `task_id` | 任务数据库主键 |
| `task_key` | 派发时的唯一任务标识 |
| `task_updated_at` | 快照对应的任务更新时间 |
| `task_snapshot` | JSON 格式不可变快照 |
| `dispatch_instruction` | 派发补充指令 |
| `created_by` | 发起用户 |
| `runner_id` | 执行 Runner |
| `repository_id` | 执行仓库 |
| `base_branch` | 基础分支快照 |
| `branch_name` | 本次执行分支 |
| `codex_thread_id` | Codex thread ID，创建后立即写入 |
| `status` | 执行状态 |
| `lease_expires_at` | Runner 领取租约到期时间 |
| `cancel_requested_at` | 取消请求时间 |
| `result_summary` | 最终摘要 |
| `result_payload` | 结构化最终结果 JSON |
| `error_code` | 稳定错误码 |
| `error_message` | 面向用户的错误信息 |
| `created_at` | 创建时间 |
| `claimed_at` | 领取时间 |
| `started_at` | 开始运行时间 |
| `finished_at` | 终态时间 |

约束：

- `codex_thread_id` 非空时唯一；
- `(created_by, client_request_id)` 唯一；
- 为 `(task_id, status)`、`(runner_id, status, created_at)` 建立查询索引。

### 8.6 `codex_run_events`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `run_id` | 执行 ID |
| `sequence_no` | Runner 内单调递增序号 |
| `event_type` | 白名单事件类型 |
| `event_payload` | 经过裁剪的 JSON |
| `created_at` | 创建时间 |

唯一约束：`(run_id, sequence_no)`，用于事件重试幂等。

允许持久化的事件类型：

- `status_changed`
- `command_started`
- `command_completed`
- `file_changed`
- `verification_completed`
- `input_requested`
- `run_completed`
- `run_failed`

### 8.7 `codex_run_messages`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `run_id` | 执行 ID |
| `sender_user_id` | 发送用户 |
| `content` | 补充指令 |
| `status` | `pending/claimed/consumed` |
| `claimed_at` | Runner 领取时间 |
| `created_at` | 创建时间 |
| `consumed_at` | Codex turn 启动确认时间 |

## 九、认证与权限

### 9.1 用户侧

用户侧接口继续使用现有 JWT。

权限固定为：

- 只有项目创建者可以连接 Runner；
- 只有项目创建者可以配置项目仓库绑定；
- 只有项目创建者可以将项目任务交给 Codex；
- 只有本次执行的发起者可以补充指令或取消执行；
- 项目成员只能查看自己原本有权访问的任务和关联执行摘要。

限制派发权限是为了避免普通项目成员通过编辑任务描述，在项目创建者的 Runner 和代码仓库中触发任意代码执行。

### 9.2 Runner 侧

Runner 通过一次性连接码注册。注册成功后，服务端只返回一次随机 Runner Token，Runner 保存到本地受限配置文件中。

服务端仅保存 Token 哈希。Runner 请求使用：

```text
Authorization: Bearer <runner-token>
```

Runner API 使用独立 `CodexRunnerAuthFilter`。现有 `JwtAuthFilter` 对 `/api/codex-runner/**` 明确跳过。`/api/codex-runner/register` 只校验一次性连接码；其余 Runner 路径只校验 Runner Token。禁止在同一路径同时尝试 JWT、连接码和 Runner Token。

### 9.3 Codex 身份

Codex 身份只存在 Runner 所在机器。Runner 使用该机器已经完成的 Codex 登录或本地安全注入的 `CODEX_API_KEY`。Linear Lite Server、浏览器和数据库均不接触 Codex 凭据。

## 十、接口设计

### 10.1 用户接口

#### 创建 Runner 连接码

```http
POST /api/codex-runners/enrollment-codes
```

响应一次性返回连接码和过期时间。

#### 查询与撤销 Runner

```http
GET    /api/codex-runners
DELETE /api/codex-runners/{runnerId}
```

撤销 Runner 后，其 Token 立即失效；绑定该 Runner 的全部非终态执行统一进入 `failed`，错误码为 `runner_revoked`。

#### 读取项目 Codex 配置

```http
GET /api/projects/{projectId}/codex-binding
```

#### 配置项目 Codex 仓库

```http
PUT /api/projects/{projectId}/codex-binding
```

请求体：

```json
{
  "runnerId": 12,
  "repositoryId": 35,
  "baseBranch": "main"
}
```

服务端校验 Runner 属于当前用户、仓库属于该 Runner、分支名非空。

#### 派发任务

```http
POST /api/tasks/{taskKey}/codex-runs
```

请求体：

```json
{
  "clientRequestId": "019f89c2-fc51-7e10-8dca-ce9a7cd63b94",
  "instruction": "优先补齐后端测试，再修改前端。"
}
```

规则：

- `clientRequestId` 必填；
- `(created_by, clientRequestId)` 唯一；
- 重试同一请求返回原执行，不重复创建；
- 事务内锁定任务行，存在非终态执行时返回 `409`；
- 任务项目必须存在有效绑定；
- Runner 必须未撤销且最近心跳有效；
- 在同一事务内生成任务快照并插入 `queued` 执行。

#### 查询任务执行

```http
GET /api/tasks/{taskKey}/codex-runs
GET /api/codex-runs/{runId}
GET /api/codex-runs/{runId}/events?afterSequence={sequence}
```

#### 补充指令

```http
POST /api/codex-runs/{runId}/messages
```

仅允许对 `needs_input` 状态发送。写入消息后状态仍保持 `needs_input`，直到 Runner 原子消费消息并转换为 `running`。

#### 取消执行

```http
POST /api/codex-runs/{runId}/cancel
```

`queued` 执行直接进入 `canceled`；其他非终态执行写入 `cancel_requested_at`，由 Runner 停止当前 turn 后上报 `canceled`。

### 10.2 Runner 接口

Runner 接口统一位于：

```text
/api/codex-runner/**
```

#### 注册 Runner

```http
POST /api/codex-runner/register
```

使用一次性连接码换取 Runner ID 和 Token。

#### 心跳与仓库上报

```http
PUT /api/codex-runner/heartbeat
```

请求包含 Runner 版本和仓库清单。服务端按 `(runnerId, repositoryKey)` 覆盖仓库元数据，不接受绝对路径。

#### 原子领取执行

```http
POST /api/codex-runner/runs/claim
```

服务端按固定优先级返回一条执行：

1. 当前 Runner 租约已过期的 `claimed/running/needs_input` 执行；
2. 绑定到当前 Runner 的最早一条 `queued` 执行。

领取 `queued` 执行时在事务内完成：

```text
queued → claimed
claimed_at = now
lease_expires_at = now + 60s
```

#### 续租

```http
PUT /api/codex-runner/runs/{runId}/lease
```

Runner 每 20 秒续租。租约过期后只允许原 Runner 重新领取，不允许其他 Runner 接管。

重新领取已有非终态执行时保持原状态、worktree 和 `codexThreadId`，Runner 必须按本地持久化状态恢复，不创建新 thread。

#### 写入 thread ID

```http
PUT /api/codex-runner/runs/{runId}/thread
```

Runner 在收到 App Server `thread.started` 后，先将 `runId → codexThreadId` 写入本地状态文件，再调用此接口。数据库已有不同 thread ID 时返回冲突。

#### 上报事件

```http
POST /api/codex-runner/runs/{runId}/events
```

按 `sequenceNo` 幂等写入。服务端拒绝白名单之外的事件类型。

#### 获取待处理消息

```http
POST /api/codex-runner/runs/{runId}/messages/claim
```

原子获取最早一条 `pending` 消息并标记 `claimed`。Runner 先把 `messageId` 写入本地执行状态，再使用 App Server 恢复 `codexThreadId`；收到 App Server `turn.started` 后调用消息确认接口，将消息标记为 `consumed`。

```http
PUT /api/codex-runner/runs/{runId}/messages/{messageId}/consumed
```

Runner 在消息 `claimed` 后崩溃时，只允许同一 Runner 重新领取该消息。本地状态无法确认消息是否已经进入 Codex turn 时，本次执行以 `message_delivery_state_lost` 失败，禁止静默重发。

#### 上报终态

```http
POST /api/codex-runner/runs/{runId}/complete
```

请求只能将执行转换为 `completed`、`failed` 或 `canceled`，并携带结构化结果或稳定错误码。

## 十一、Runner 设计

### 11.1 目录

新增独立目录：

```text
linear-lite-codex-runner/
├── package.json
├── package-lock.json
├── tsconfig.json
├── src/
│   ├── cli.ts
│   ├── config.ts
│   ├── serverClient.ts
│   ├── repositoryRegistry.ts
│   ├── worktreeManager.ts
│   ├── codexAppServerClient.ts
│   ├── codexAppExecutor.ts
│   ├── eventSanitizer.ts
│   └── runLoop.ts
└── tests/
```

Runner 使用独立依赖锁，不进入前端 Vite 构建，也不进入 Spring Boot 单 JAR。Runner 启动前必须确认本机 Codex App Server daemon 可用；它不自行创建独立 CLI 会话。

### 11.2 本地配置

本地配置不提交仓库，至少包含：

```json
{
  "serverUrl": "https://linear-lite.example.com",
  "runnerToken": "仅保存在当前开发机的受限文件中",
  "repositories": {
    "linear-lite": {
      "path": "/absolute/path/to/target-repository",
      "remoteIdentity": "github.com/example/linear-lite",
      "defaultBranch": "main"
    }
  }
}
```

### 11.3 Codex 环境

Runner 只连接本机 Codex App Server，不向新建的 Codex CLI 子进程传递环境变量。App Server daemon 由当前 macOS 登录用户启动，并使用该用户已登录的 Codex App 身份。

Runner 的进程环境只保留连接 daemon 和 Git worktree 所需的：

- `PATH`
- `CODEX_HOME`
- `TMPDIR`
- App Server 本机 socket/控制通道配置

禁止把 Runner 进程完整环境直接透传给 App Server。Codex sandbox 固定为 `workspace-write`，Codex 网络访问默认关闭。`git fetch` 由 Runner 在创建 App Server thread 前完成。

### 11.4 本地执行状态

Runner 在自己的状态目录持久化：

```text
runId
codexThreadId
repositoryKey
worktreePath
branchName
lastSequenceNo
```

Runner 重启后：

- 数据完整且服务端执行仍为非终态：恢复同一 thread 和 worktree；
- 缺失 `codexThreadId` 且执行已经进入 `running`：上报 `failed/thread_identity_lost`；
- worktree 不存在：上报 `failed/worktree_missing`；
- 已领取消息的本地投递状态缺失：上报 `failed/message_delivery_state_lost`；
- 禁止新建另一个 thread 静默继续同一执行。

### 11.5 Worktree 保留

所有终态执行的 worktree 和分支均保留，防止丢失未提交变更。本期不提供自动清理。用户确认结果后由本地 Git 工具处理。

## 十二、Codex 输入与输出契约

### 12.1 固定提示词

Runner 以固定模板生成首轮 prompt：

```text
你正在执行 Linear Lite 任务 {taskKey}。

项目：{projectIdentifier} / {projectName}
标题：{title}
描述：{description}
状态：{status}
优先级：{priority}
标签：{labels}
截止日期：{dueDate}
计划开始日期：{plannedStartDate}
派发补充指令：{dispatchInstruction}

工作约束：
1. 当前工作目录是本次任务的独立 Git worktree。
2. 遵守仓库中的 AGENTS.md 及其下级约束。
3. 在任务范围内完成实现和验证。
4. 不提交、不推送、不创建合并请求。
5. 需要业务确认时停止修改并返回 needs_input。
6. 最终响应必须符合给定 JSON Schema。
```

任务字段只从 `taskSnapshot` 的固定字段读取，不从其他字段名或历史字段回退。

### 12.2 最终输出 Schema

每一轮执行使用同一份结构化输出：

```json
{
  "type": "object",
  "properties": {
    "outcome": {
      "type": "string",
      "enum": ["completed", "needs_input", "failed"]
    },
    "summary": { "type": "string" },
    "question": { "type": ["string", "null"] },
    "changedFiles": {
      "type": "array",
      "items": { "type": "string" }
    },
    "verification": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "command": { "type": "string" },
          "result": { "type": "string" }
        },
        "required": ["command", "result"],
        "additionalProperties": false
      }
    },
    "blockers": {
      "type": "array",
      "items": { "type": "string" }
    }
  },
  "required": [
    "outcome",
    "summary",
    "question",
    "changedFiles",
    "verification",
    "blockers"
  ],
  "additionalProperties": false
}
```

映射规则：

- `completed` → `codex_runs.status=completed`
- `needs_input` → `codex_runs.status=needs_input`，`question` 必须非空
- `failed` → `codex_runs.status=failed`，`blockers` 必须非空

## 十三、事件过滤

Runner 可以接收 App Server 的完整协议事件，但只允许向服务端上报以下内容：

- 当前阶段；
- 命令名称和退出状态；
- 被修改文件的仓库相对路径；
- 验证命令和结果摘要；
- Codex 明确提出的问题；
- 最终结构化结果；
- 稳定错误码。

禁止上报：

- reasoning；
- 环境变量；
- Token、Cookie、Authorization Header；
- 完整 stdout/stderr；
- 文件完整内容；
- Runner 本地绝对路径；
- `~/.codex` 内容。

事件净化在 Runner 完成，服务端再次校验事件类型和最大长度。

## 十四、前端设计

### 14.1 项目设置

项目设置新增“Codex 执行”区域，仅项目创建者可操作：

- Runner 在线状态；
- 已注册仓库；
- 当前绑定仓库；
- 基础分支；
- 创建 Runner 连接码；
- 撤销 Runner；
- 保存项目绑定。

未绑定时明确显示配置阻塞，不展示伪可用的“交给 Codex”按钮。

### 14.2 任务入口

`TaskEditor.vue` 标题区域新增“交给 Codex”按钮，仅在以下条件全部成立时显示：

- 当前用户是项目创建者；
- 项目存在有效 Codex 绑定；
- Runner 在线；
- 当前任务不存在非终态执行。

点击后打开确认面板，展示：

- `taskKey` 与标题；
- Runner；
- 仓库；
- 基础分支；
- 任务快照摘要；
- 补充指令输入框；
- 明确的“开始执行”按钮。

### 14.3 执行面板

任务详情增加 Codex 执行面板：

- 当前状态；
- 分支名；
- 已完成阶段；
- 文件变更列表；
- 验证结果；
- Codex 提问和用户回复入口；
- 最终摘要；
- 错误信息；
- 取消按钮。

前端使用短轮询获取事件增量，游标为 `sequenceNo`。本期不新增 WebSocket 或 SSE。

## 十五、一致性与并发

### 15.1 派发幂等

前端在用户打开确认面板时生成 `clientRequestId`，点击重试复用同一值。服务端通过 `(created_by, clientRequestId)` 返回同一个 `CodexRun`。

### 15.2 单任务单活动执行

创建执行时在事务内锁定 `tasks` 行，并查询该任务所有非终态执行。若已存在则返回 `409 CODEX_RUN_ALREADY_ACTIVE`。

终态执行不阻止重新派发；重新派发生成新的 run ID、分支和 thread。

### 15.3 Runner 领取

领取使用数据库条件更新，只有 `status=queued` 且 `runner_id=当前 Runner` 的执行可以进入 `claimed`。同一执行无法被并发领取两次。

### 15.4 事件幂等

Runner 对每个 run 维护单调递增 `sequenceNo`。网络重试重复提交同一事件时，唯一约束保证只写入一次。

### 15.5 消息单次消费

Runner 在事务内领取一条 `pending` 消息并标记 `claimed`。收到 App Server `turn.started` 后再标记 `consumed`。已消费消息不得再次发送给 Codex；投递状态无法判定时执行失败，不做重复投递。

## 十六、错误处理

| 错误码 | 场景 | 结果 |
| --- | --- | --- |
| `CODEX_BINDING_NOT_CONFIGURED` | 项目未绑定 Runner/仓库 | 禁止派发 |
| `CODEX_RUNNER_OFFLINE` | Runner 心跳超时 | 禁止派发 |
| `CODEX_RUN_ALREADY_ACTIVE` | 任务已有非终态执行 | 返回现有执行 ID |
| `REPOSITORY_NOT_REGISTERED` | Runner 本地无该 `repositoryKey` | 执行失败 |
| `GIT_FETCH_FAILED` | 无法获取指定基础分支 | 执行失败 |
| `WORKTREE_CREATE_FAILED` | 无法创建隔离工作区 | 执行失败 |
| `CODEX_AUTH_UNAVAILABLE` | Runner 未完成 Codex 身份配置 | 执行失败 |
| `CODEX_THREAD_START_FAILED` | App Server 无法创建 thread | 执行失败 |
| `THREAD_IDENTITY_LOST` | 运行中断且 thread ID 未持久化 | 执行失败 |
| `WORKTREE_MISSING` | 恢复时 worktree 已不存在 | 执行失败 |
| `MESSAGE_DELIVERY_STATE_LOST` | 无法确定补充消息是否已进入 Codex turn | 执行失败 |
| `CODEX_OUTPUT_INVALID` | 最终结果不符合 Schema | 执行失败 |
| `RUNNER_REVOKED` | 执行期间 Runner 被撤销 | 执行失败 |

所有错误均保留 worktree，不自动删除代码变更。

## 十七、后端代码组织

新增：

```text
linear-lite-server/src/main/java/com/linearlite/server/
├── controller/
│   ├── CodexRunnerController.java
│   ├── CodexProjectBindingController.java
│   ├── CodexRunController.java
│   └── CodexRunnerAgentController.java
├── dto/codex/
├── entity/
│   ├── CodexRunner.java
│   ├── CodexRunnerEnrollmentCode.java
│   ├── CodexRepository.java
│   ├── ProjectCodexBinding.java
│   ├── CodexRun.java
│   ├── CodexRunEvent.java
│   └── CodexRunMessage.java
├── mapper/
├── service/
│   ├── CodexRunnerService.java
│   ├── CodexProjectBindingService.java
│   └── CodexRunService.java
└── filter/
    └── CodexRunnerAuthFilter.java
```

约束：

- 使用现有 MyBatis-Plus；
- 禁止 `JdbcTemplate`；
- 不通过 Feign 跨库查询；
- Controller 不直接操作 Mapper；
- 状态转换、幂等、权限和租约统一在 Service；
- DTO 字段只对应一条固定数据路径，不读取别名字段或历史字段。

## 十八、实施顺序

### 第 1 步：Runner 注册与项目绑定

- 新增 Runner、连接码、仓库、项目绑定表；
- 新增 Runner Token 认证；
- 实现 Runner 注册、心跳、仓库上报；
- 完成项目设置中的 Runner 与仓库绑定。

完成标准：项目创建者可以连接一台 Runner，并将项目绑定到 Runner 上报的唯一仓库。

部署前置条件：Linear Lite 服务端已经上线；Runner 只在项目创建者本机安装、注册和启动。不得在应用服务器尝试注册 Runner 或配置 Codex 登录。

### 第 2 步：执行记录与派发

- 新增 `codex_runs`、events、messages；
- 实现任务快照；
- 实现派发幂等和单任务单活动执行；
- 实现 Runner 原子领取、租约和状态机；
- 完成任务详情派发入口和状态展示。

完成标准：点击“交给 Codex”后，Runner 可以领取带精确 `taskKey` 的执行记录。

### 第 3 步：Codex App Server POC 与 Git 隔离

- 创建独立 Runner 包；
- 实现本地仓库白名单；
- 实现 fetch、分支、worktree；
- 启动并连接本机 `codex app-server` daemon；
- 用 App Server 协议创建/恢复 thread，并验证其在 Codex App 中可见；
- 持久化 `codexThreadId` 和本地执行状态；
- 使用结构化输出 Schema。

完成标准：Codex App 展示并继续同一 thread；Codex 在独立 worktree 中完成真实代码修改，用户当前工作区不发生变化。

### 第 4 步：事件、补充输入与恢复

- 实现事件净化与幂等上报；
- 实现 `needs_input`；
- 实现用户消息单次消费和 `resumeThread`；
- 实现 Runner 重启恢复；
- 实现取消和稳定错误码。

完成标准：Codex 提问后，用户可在 Linear Lite 中回复并继续同一 thread；Runner 重启后不会创建重复 thread。

## 十九、测试方案

### 19.1 后端

- 连接码过期、重复消费、Token 撤销；
- Runner 只能访问自身执行；
- 普通项目成员不能配置或派发；
- 项目绑定校验 Runner 与仓库归属；
- `clientRequestId` 重试不重复建 run；
- 同任务并发派发只成功一次；
- Runner 并发领取只成功一次；
- 租约续期和原 Runner 恢复；
- 非法状态转换拒绝；
- 事件序号重复不重复写入；
- 消息只能消费一次；
- 任务变更后快照保持不变；
- Runner 撤销后认证立即失败。

### 19.2 Runner

- 未注册仓库拒绝执行；
- 服务器值不能被解释为本地路径；
- fetch 失败不创建 thread；
- worktree 与父仓库隔离；
- 分支名稳定且唯一；
- App Server 首个 thread 事件立即落本地状态，并在 Codex App 可见；
- 事件净化移除路径、环境变量和敏感头；
- 结构化输出校验；
- `needs_input` 使用原 thread 恢复；
- 本地状态缺失时按稳定错误码失败；
- 取消请求终止执行并保留 worktree。

### 19.3 前端

- 非项目创建者不显示派发入口；
- 未配置、Runner 离线、已有活动执行时按钮状态正确；
- 重复点击复用 `clientRequestId`；
- 执行事件按游标增量展示；
- `needs_input` 提问与回复；
- 任务派发后被修改时显示快照提示；
- 终态摘要、文件与验证结果正确展示。

### 19.4 真实验收

1. 创建 Linear Lite 任务 `ENG-N`；
2. 项目创建者在自己的开发机配置并启动本地 Runner，确认服务器只收到出站心跳；
3. 点击“交给 Codex”；
4. 验证数据库执行记录中的 `taskKey` 与页面任务一致；
5. 验证 Runner 基于指定远程分支创建独立 worktree；
6. 验证 Codex thread 的首轮 prompt 包含固定任务快照；
7. 验证文件只在 worktree 中修改；
8. 验证测试结果和最终摘要回到 Linear Lite；
9. 制造一次 `needs_input`，从 Linear Lite 回复并继续；
10. 重启 Runner，验证同一 `codexThreadId` 可以恢复；
11. 验证当前用户工作区和未提交内容未受影响。

## 二十、完成标准

本方案完成的定义：

- Linear Lite 项目可以绑定一台受控 Runner 和一个唯一仓库；
- 项目创建者可以从确定任务发起 Codex 执行；
- `taskKey`、任务快照、run ID、thread ID 全链路可追踪；
- Codex 只在独立 worktree 中执行；
- 每条 Linear Lite 派发都在本机 Codex App 中可见并可继续；
- Linear Lite Server 不接触 Codex 凭据和本地绝对路径；
- 执行具备幂等、单任务单活动、租约、恢复和取消能力；
- 用户可以在 `needs_input` 后继续原 thread；
- 完成结果符合固定 JSON Schema；
- 后端、前端、Runner 自动化测试通过；
- `mvn test`、前端 `npm test`/`npm run build`、Runner `npm test`/`npm run build` 全部通过。

## 二十一、参考资料

- [OpenAI：Use Codex in Linear](https://learn.chatgpt.com/docs/third-party/linear)
- [OpenAI Codex CLI](https://github.com/openai/codex)
- [OpenAI Codex App Server CLI 命令](https://developers.openai.com/codex/cli/reference/)
