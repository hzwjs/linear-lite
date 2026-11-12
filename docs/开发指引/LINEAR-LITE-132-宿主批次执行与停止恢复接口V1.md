# LINEAR-LITE-132 宿主批次执行与停止恢复接口

## 身份与批次范围

宿主使用 OAuth 访问令牌调用普通宿主接口。服务端从授权记录取得用户、设备和授权 ID，不接受请求体指定身份。批次保存项目、绑定版本、任务清单、槽位、时长和尝试次数；宿主接受批次时签发能力凭据，凭据包含批次、用户、项目、绑定、设备、授权、宿主实例和执行代次。

宿主先读取批次上下文，取得项目根目录的物理锁，再接受批次。接受结果中的 `batchToken` 保存到本机凭据存储；凭据不得进入日志或任务资料。接受响应丢失且凭据未保存时，保留物理锁并请求人工处理。

## 普通宿主接口

以下接口均为 `POST`，路径前缀为 `/api/collaboration-host`；成功响应的数据位于 `data`，响应携带 `X-Linear-Lite-Server-Time` 与 `Cache-Control: no-store`。

| 路径 | 请求体 | 用途 |
|---|---|---|
| `/batches/pending` | `{}` | 返回当前授权和设备可领取批次的 `items` |
| `/batches/context` | `projectId`、`batchId` | 读取绑定目录、任务清单与预算 |
| `/batches/accept` | `projectId`、`batchId`、`hostInstanceId` | 接受持久批次，返回 `batchToken`、`runEpoch`、`runLeaseUntil`、`deadlineAt`、`expiresAt` |
| `/batches/renew` | `batchToken` | 返回 `desiredIntent`、`runLeaseUntil`、`deadlineAt`；停止意图不延长租约 |
| `/environment/observe` | `batch`、`available` | 观察任务依赖和可执行状态 |
| `/tasks/claim` | `batch`、任务与宿主、Agent 标识及操作 ID | 领取任务 |
| `/attempts/expand-scopes` | `batch`、任务、执行命令与申请范围 | 扩展写入范围 |
| `/attempts/renew` | `batch`、任务与执行命令 | 续租任务 |
| `/attempts/checkpoint` | `batch`、任务、执行命令与检查点 | 写入检查点 |
| `/attempts/complete` | `batch`、任务、执行命令、成果及停止证明 | 提交任务成果 |

`batch` 包含 `batchToken`、`bindingId`、`deviceId`、`projectDirectory`。执行接口核对宿主 OAuth 与批次凭据的用户、设备和授权，领域服务核对持久批次的 `RUN` 意图、绑定版本、宿主实例、执行代次和租约。MCP 使用认证用户和宿主持有的批次凭据进入同一领域门禁。

批次租约到期后不能续活或由新宿主接管；宿主进入停止恢复。任务租约不超过批次租约和批次截止时间，批次续租不会延长已有任务租约，任务续租需调用对应接口。领取新任务时校验未确认停止的执行数和尝试次数；已冻结的执行占用槽位。

## 停止与恢复

恢复接口前缀为 `/api/collaboration-host/recovery`，只接受原批次签名作为能力来源，不依赖原 OAuth 访问令牌。签名允许过期，服务端仍核对持久批次、原授权、设备、宿主实例、执行代次及任务领取证明；恢复不授予领取、续租或成果写入权限。

| 路径 | 用途 |
|---|---|
| `/read` | 使用 `batchToken` 和原执行证明读取恢复状态 |
| `/request-stop` | 使用 `batchToken` 和原执行证明记录停止请求 |
| `/confirm-stop` | 使用 `batchToken`、原执行证明及停止事实确认任务停止 |
| `/recover-claim` | 使用原领取操作证明、宿主和 Agent 标识恢复未收到领取响应的执行 |
| `/finish` | 使用 `batchToken`、`reason` 和 `confirmation` 收口批次 |

`confirmation` 包含 `stopped: true` 和非空 `detail`。宿主先停止进程，按执行逐项请求并确认停止，再调用 `/finish`；即使批次没有领取任务，也需要提供停止事实。所有执行确认停止后批次才能结束并释放资源。重试完成操作返回原结束状态。

自然结束的 `reason` 可为 `completed`、`budget_exhausted`、`host_stopped` 或 `needs_decision`。`completed` 要求所选任务满足完成和当前输入成果条件，`budget_exhausted` 要求达到截止时间或尝试预算。网页停止、项目删除或授权撤销触发停止时，批次保留首个停止原因；`endReason` 在停止中即可非空，恢复不会覆盖该原因。

旧的无状态签发、网页执行和网页恢复路由已移除；网页绑定与输入接口保留。待执行批次的后台轮询和驱动属于 LINEAR-LITE-134。
