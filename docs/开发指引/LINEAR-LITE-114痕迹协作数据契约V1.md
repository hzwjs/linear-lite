# LINEAR-LITE-114 痕迹协作数据契约 V1

本契约定义本机项目绑定、任务输入、依赖与执行尝试的数据基础。领域服务、协作接口、执行宿主和界面功能由后续任务实现。

## 1. 本机项目绑定

`project_device_bindings` 的 `id` 是绑定记录主键，`user_id`、`project_id`、`device_id` 构成唯一键；`project_directory` 保存设备上的项目根目录。`docs/` 由项目根目录派生，缺少独立目录字段。

`ProjectDeviceBinding` 对应表字段，`ProjectDeviceBindingResponse` 作为服务响应契约。`ProjectDeviceBindingMapper.selectForUpdate` 按用户、项目和设备读取并锁定记录；项目权限、设备归属、路径规范化和绑定变更的执行保护由绑定服务承担。

## 2. 任务与依赖

| 数据源字段 | 服务与前端字段 | 含义 |
|---|---|---|
| `tasks.agent_execution_enabled` | `agentExecutionEnabled` | 用户对 Agent 执行的授权，初始值为 `false` |
| `tasks.input_revision` | `inputRevision` | 协作输入版本，初始值为 `1` |
| `tasks.write_scopes` | `writeScopes` | 计划写入的项目相对路径字符串数组，初始值为 `[]` |

任务详情、收藏和创建或更新结果从 `Task` 输出；任务列表从 `TaskListItemResponse` 输出。各入口的数据字段来自 `tasks`。`StringListJsonTypeHandler` 按字符串数组读写范围，拒绝对象、数字与其他非字符串元素。前端接口类型要求服务响应携带三个字段，`toTask` 传递字段值；本地乐观创建对象在服务端持久化前没有这些事实。

`task_dependencies` 的 `task_id`、`prerequisite_task_id` 表达前置边，唯一键拒绝重复边。父子关系保持 `tasks.parent_id` 的汇总语义。跨项目、自依赖与环检查属于依赖领域服务职责。

## 3. 执行尝试

每次领取创建一行 `task_execution_attempts`。重试创建新行，历史行保留输入、成果与停止事实。

| 字段 | 含义 |
|---|---|
| `task_id`、`binding_id`、`batch_id` | 任务、用户项目设备绑定及批次归属 |
| `host_instance_id`、`agent_instance_id` | 宿主与子代理实例 |
| `claim_token_hash` | 领取标识摘要，实体 JSON 和公共响应排除此字段 |
| `input_revision` | 本次执行采用的任务输入版本 |
| `prerequisite_versions` | 前置成果版本 JSON 快照；通过任务、执行尝试及输入版本引用前置事实 |
| `held_write_scopes` | 本次持有的项目相对路径字符串数组 |
| `status` | 本次执行状态，禁止作为任务业务状态副本 |
| `lease_until` | 本次领取的租约到期时间 |
| `checkpoints` | 执行检查点 JSON 数组，初始值为 `[]` |
| `result_evidence` | 成果、完成条件结果与稳定项目检查点的 JSON 证据 |
| `stop_reason`、`stop_requested_at` | 停止原因与请求时间 |
| `stop_confirmed_at`、`stop_confirmation` | 宿主确认停止的时间与事实 |
| `created_at`、`updated_at`、`finished_at` | 记录建立、更新和执行结束时间 |

执行状态使用 `running`、`stop_requested`、`succeeded`、`failed`、`stopped`：`running` 表达本次领取，`stop_requested` 表达停止意图，`succeeded` 表达成果交付，`failed` 表达执行失败，`stopped` 表达执行停止。任务业务状态以 `tasks.status` 为准，禁止从执行状态反推任务完成。

`held_write_scopes` 是领取时的范围快照；租约到期或停止请求不会清空该字段。范围释放以停止确认为条件，状态变化、租约到期和停止请求缺少停止进程的证明。

对象与数组使用 JSON 保存；`prerequisite_versions`、`checkpoints`、`result_evidence`、`stop_confirmation` 通过 `JsonNode` 保留结构。范围使用字符串数组专用处理器，禁止将 JSON 对象转为路径字符串。领取标识原文禁止进入执行实体、公共 DTO、日志、文档与检查点。

## 4. 检查点追加契约

`TaskExecutionCheckpoint` 包含 `operationId`、`agentInstanceId`、`occurredAt`、`finding`、`artifactRefs` 和 `blockedReason`。检查点记录事实、成果引用与阻塞原因；心跳用途为续租。

`TaskExecutionAttemptMapper.appendCheckpoint(id, expectedCount, checkpointJson)` 通过 JSON 数组长度条件追加一条检查点。返回 `1` 表示本次追加成功；返回 `0` 表示预期数量或执行记录发生变化，调用者需要读取事实并处理冲突。

领取权、租约、输入版本、检查点结构和 `operationId` 去重由后续生命周期服务在执行记录行锁内校验。Mapper 的数量条件缺少授权和幂等校验能力，禁止将 Mapper 行数当作完整业务操作结果。

## 5. 本卡边界

`schema.sql` 包含全量结构和已有任务表字段的增量归档。目录绑定读写入口、依赖和输入失效服务、领取及续租服务、REST/MCP 操作、Codex 宿主和界面交互由后续卡片实现。现有 Pi 人工指令使用原有目录配置，协作路径禁止把它作为备用来源。
