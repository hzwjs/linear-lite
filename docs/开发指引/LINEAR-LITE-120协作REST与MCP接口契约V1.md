# LINEAR-LITE-120 协作 REST 与 MCP 接口契约 V1

REST 与 MCP 使用同一输入 schema 和领域服务。REST 路径前缀为 `/api/collaboration`，各操作均以 JSON 对象 POST；响应使用现有 `ApiResponse`，业务结果位于 `data`。MCP 的业务结果位于 `structuredContent`，与 REST 的 `data` 对应。认证用户来自服务器认证上下文，请求字段不包含 `userId`。

| MCP 工具 | REST 路径 | 输入重点 | MCP scope |
| --- | --- | --- | --- |
| `save_project_device_binding` | `/bindings/save` | `projectId`、`deviceId`、`projectDirectory` | write |
| `get_project_device_binding` | `/bindings/get` | `projectId`、`deviceId` | read |
| `delete_project_device_binding` | `/bindings/delete` | `projectId`、`deviceId` | write |
| `issue_collaboration_batch` | `/batches/issue` | `projectId`、`bindingId`、`deviceId`、`projectDirectory`、`taskIds`、`expiresAt` | write |
| `replace_task_collaboration_inputs` | `/inputs/replace` | `taskKey`、`inputs` | write |
| `get_task_collaboration_inputs` | `/inputs/get` | `taskKey` | read |
| `observe_collaboration_environment` | `/environment/observe` | `batch`、`available` | write |
| `claim_collaboration_task` | `/tasks/claim` | `batch`、`taskId`、`inputRevision`、`hostInstanceId`、`agentInstanceId`、`operationId`、`available` | write |
| `expand_collaboration_write_scopes` | `/attempts/expand-scopes` | `batch`、`taskId`、`command`、`requestedScopes` | write |
| `renew_collaboration_attempt` | `/attempts/renew` | `batch`、`taskId`、`command` | write |
| `append_collaboration_checkpoint` | `/attempts/checkpoint` | `batch`、`taskId`、`command`、`checkpoint` | write |
| `complete_collaboration_task` | `/attempts/complete` | `batch`、`taskId`、`command`、`evidence`、`confirmation` | write |
| `request_collaboration_stop` | `/attempts/request-stop` | `projectId`、`bindingId`、`deviceId`、`taskId`、`command`、`reason` | write |
| `confirm_collaboration_stop` | `/attempts/confirm-stop` | `projectId`、`bindingId`、`deviceId`、`taskId`、`command`、`confirmation` | write |

`expiresAt` 为 ISO-8601 时间点，例如 `2099-01-01T00:00:00Z`；签发结果包含 `batchId`、`batchToken` 和 `expiresAt`。观察会记录过期尝试的停止意图，使用 write scope。绑定管理、签发与人工输入配置发生在领取前；执行写入使用批次与执行命令，停止恢复采用独立归属校验。

## 对象字段

所有标识和版本为正整数。字段名称、路径、操作标识和自由文本保留请求原文，服务不执行自动 trim。未知字段、错误类型及缺失必填项遭拒，错误文案不包含请求原文。

- `inputs`：`inputRevision`、`agentExecutionEnabled`、`writeScopes`、`publicContextRefs`、`prerequisiteTaskIds`。`inputRevision` 是任务当前版本；替换时传入读取到的版本作为并发前提。公共引用元素为 `{documentId, version}`；依赖 ID 来源为依赖表。
- `batch`：`batchToken`、`bindingId`、`deviceId`、`projectDirectory`。项目根目录来自绑定，`docs/` 从项目根目录派生。
- `available`：数组，每项为 `{attemptId, inputRevision}`，由宿主声明当前设备可用的前置成果。空数组表示没有可用声明。
- 业务 `command`：`attemptId`、`claimToken`、`inputRevision`、`hostInstanceId`、`agentInstanceId`、`operationId`。停止恢复的 `command` 不要求 `inputRevision`，其余字段必填。
- `checkpoint`：`occurredAt`（ISO-8601 本地日期时间）、`artifactRefs` 必填，`finding`、`blockedReason` 可省略；操作标识与 Agent 实例从 `command` 取得。
- `evidence`：`conditions`、`changedPaths`、`artifactRefs`、`projectCheckpoint`。每个条件包含 `condition`、`result` 和 `evidenceRefs`。条件、证据引用、产物引用和项目检查点由领域服务校验；修改位置必须属于持有范围。
- `confirmation`：`stopped`、`detail`。宿主确认执行单元及关联写入进程停止；服务器保存声明与停止时间。

`get_task_collaboration_inputs` 在项目协调事务中读取任务和依赖，返回 `inputRevision`、`agentExecutionEnabled`、`writeScopes`、`publicContextRefs`、`prerequisiteTaskIds`；接口不另建 ready 状态。`replace_task_collaboration_inputs` 返回任务记录，读取更新后的输入与依赖使用 `get_task_collaboration_inputs`。

## 结果与错误

观察返回任务、输入版本、前置结果、检查点、资源归属及阻塞原因。领取、扩张、续租、检查点、完成和停止操作返回领域结果；`accepted=false` 为结构化业务拒绝，HTTP 返回 200，MCP 返回同一业务体。领域事务提交后返回拒绝结果，使过期或输入失效引起的停止意图得以保留。

参数 schema 错误在 REST 返回 400，在 MCP 返回 JSON-RPC `-32602`；领域权限、冲突及业务错误沿现有异常映射，两个入口保留同一原因。停止请求和确认采用过期恢复入口，不要求有效批次或当前租约；认证用户、设备、绑定、执行实例和领取标识必须匹配。

批次凭据和领取标识作为请求凭据及领取/签发结果传输；执行历史与回执排除凭据。应用日志和任务材料禁止保存凭据。宿主负责文件可用性、目录物理解析与进程停止，接口不提供操作系统隔离。
