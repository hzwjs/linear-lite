# LINEAR-LITE-131 协作批次控制接口 V1

## 作用与边界

服务端保存批次控制意图、固定任务集合、运行预算及执行归属。网页用户认证后调用 `/api/collaboration/batches` 下的控制接口，用户身份从登录上下文取得，请求正文中的用户字段不参与鉴权。列表、详情和控制操作限制为当前用户所属项目及当前用户创建的批次。

本交付提供持久批次控制和内部领取原语；宿主执行入口、批次执行签名、领取与续租门禁由 LINEAR-LITE-132 接入。现有 `/api/collaboration/batches/issue` 属于已有宿主链路，不承载新控制批次的持久状态。

一个用户、一个项目、一台设备对应一条绑定，批次固定绑定 ID、绑定版本和宿主授权记录 ID。项目根目录保存在原绑定中，批次不新增目录字段。

## 启动

`POST /api/collaboration/batches/start`：

```json
{
  "projectId": 6,
  "operationId": "browser-start-1",
  "bindingId": 10,
  "bindingRevision": 2,
  "taskIds": [101, 102],
  "maxSlots": 2,
  "maxDurationMs": 3600000,
  "maxAttempts": 5
}
```

服务端检查用户项目权限、绑定归属与版本、宿主在线授权、任务项目归属、执行授权、待执行状态及输入配置。空写入范围是合法的只读计划。有效前置任务尚未交付时允许启动，任务详情返回等待原因。任务集合固定，不补入其他任务。

响应 `data` 含 `batchId`、`lifecycle`、`desiredIntent`、`intentRevision`、`displayState`。新批次为 `queued`、`RUN`、`WAITING_ACCEPT`。启动回执不包含执行令牌。

`operationId` 以项目和认证用户限定，同一操作及同一请求的重放返回原回执；换用请求或操作类型返回冲突。重放已提交回执不要求设备再次在线。事务内再次检查回执，重复并发启动不产生第二条批次。

## 停止

`POST /api/collaboration/batches/stop`：

```json
{"projectId": 6, "operationId": "browser-stop-1", "batchId": "批次标识"}
```

未领取的 `queued` 批次写入 `STOP`，生命周期置 `ended`，结束原因为 `user_stopped`。`accepted` 批次写入 `STOP` 并保留生命周期；租约有效时展示 `STOPPING`，失联时展示 `RECOVERY`。网页调用不需要任务领取标识，不伪造宿主停止确认，也不修改任务业务状态。

停止操作使用启动节中的回执幂等规则。批次停止与宿主领取按项目锁和批次行锁裁决：停止先提交则领取失败，领取先提交则停止等待执行确认。

## 列表与详情

`POST /api/collaboration/batches/list` 接收 `projectId`、`limit`（1 至 100）和可空的 `beforeBatchId`。响应 `data` 含 `items`、`hasMore`、`nextCursor`，按 `createdAt` 与 `batchId` 倒序分页。游标须属于当前用户和项目。

`POST /api/collaboration/batches/get` 接收 `projectId`、`batchId`。响应 `data.batch` 为持久批次及尝试次数，`data.tasks` 读取任务、依赖和本批次执行尝试。尝试次数按 `task_execution_attempts.batch_id` 计数，检查点和交付证据来自执行尝试。

任务读模型区分下列事实：

| 字段 | 含义 |
|---|---|
| `status`、`agentExecutionEnabled` | 当前任务业务状态与协作授权 |
| `inputsReady`、`inputsWaitingReason` | 服务端输入配置和前置成果评估 |
| `activeOrFrozen` | 项目内该任务存在未确认停止的尝试 |
| `resourceBlocked` | 同绑定的其他未确认尝试持有重叠写入范围 |
| `requiresHostArtifactCheck` | 服务端存在有效前置成果，宿主仍需确认本地成果可用 |
| `attempts` | 当前批次该任务的执行尝试与痕迹 |

`inputsReady` 不代表任务可领取：业务状态、授权、活动尝试、范围占用和本地成果可用性均会影响领取。

批次 `displayState` 为 `WAITING_ACCEPT`、`RUNNING`、`STOPPING`、`RECOVERY` 或 `ENDED`。任务等待原因来自任务输入、依赖及资源事实。批次时间字段按 UTC 绝对时间返回；任务尝试时间沿用原执行记录接口契约。

## 预算与恢复

领取批次时记录 `startedAt`、`deadlineAt`、宿主实例、执行代次与运行租约；初始租约为 30 秒。停止已领取批次不释放写入范围，宿主退出与停止确认由现有执行生命周期处理。服务进程重建、页面重试不会重置持久记录，已消耗尝试数从执行记录计算。

授权撤销与业务请求门禁的联动由 LINEAR-LITE-132 接入；131 的内部领取原语不作为网页或宿主 HTTP 入口暴露。

目录变更与绑定删除检查未结束的 `accepted` 批次，即使批次尚无任务尝试也保持冻结。项目删除先在独立事务提交批次与任务停止意图：`queued` 批次结束，`accepted` 批次等待停止确认；清理前再次检查执行状态，再删除项目批次的任务集合与批次记录。
