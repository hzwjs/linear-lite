# Codex 宿主痕迹协作集成契约核实 V1

任务：LINEAR-LITE-121。核实日期：2026-11-10。

依据：[痕迹协作改造方案 V1](../方案设计/Linear-Lite痕迹协作改造方案V1.md)。本卡交付宿主契约与能力缺口，功能实现归 LINEAR-LITE-122。

## 1. 结论与证据等级

Codex 具有会话、回合及受控命令的协议声明，当前会话提供子代理协调工具。资料与本机声明缺少覆盖全部关联写入进程的停止证明；设备绑定、宿主续租与重启恢复需要集成实现。

| 证据等级 | 本卡证据 | 适用边界 |
|---|---|---|
| 本机只读结果 | `codex --version` 返回 `codex-cli 0.164.0`；协议生成命令成功 | 证明本机 CLI 版本及其协议声明 |
| 本机会话工具契约 | `collaboration` 提供创建、观察、中断与跟进操作 | 作用于当前会话，缺少对外公用 RPC 的证明 |
| 官方在线资料 | App Server、Subagents、Goals、Scheduled tasks 文档 | 描述官方当前契约，版本与本机 CLI、桌面宿主分开记录 |
| 运行证明 | 本卡没有启动业务任务、测试线程或进程生命周期探针 | 协议声明、二进制字面量与帮助信息禁止替代运行证明 |

桌面宿主版本与托管服务连接状态待确认。`codex app-server daemon version` 返回控制 socket 缺失，该结果表示该命令缺少 daemon 连接，禁止据此推断当前桌面会话不可用。

本机协议证据保存于 [LINEAR-LITE-121-Codex协议声明V1.json](./证据/LINEAR-LITE-121-Codex协议声明V1.json)，来源为：

```sh
codex app-server generate-json-schema --out <证据目录> --experimental
```

该操作生成静态协议文件，缺少方法执行、授权与停止覆盖证明。

## 2. 会话与子代理控制入口

App Server 在初始化握手后接受请求。下表输入来自本机 `0.164.0` 协议声明，结果语义参照官方资料。

| 入口 | 输入重点 | 输出或事件 | 能力边界 |
|---|---|---|---|
| `thread/start` | `cwd`、执行配置与权限 | `thread.id` | 创建会话线程；与原生子代理等价的证据缺失 |
| `turn/start` | `threadId`、`input` | `turn.id`、回合事件 | 启动回合，禁止视为任务成果验收 |
| `thread/read` | `threadId`、`includeTurns` | 会话信息 | 读取会话状态与可用记录 |
| `thread/status/changed`、`turn/completed` | 服务端通知 | 会话或回合状态 | 表达运行状态，缺少进程树停止语义 |
| `turn/interrupt` | `threadId`、`turnId` | 请求响应及回合终态 | 请求中断，`interrupted` 表达回合中断 |
| `thread/resume` | `threadId` | 恢复后的会话信息 | 恢复上下文，禁止当作业务写入进程复活或停止确认 |

当前会话的子代理操作为：

| 工具 | 输入重点 | 输出与作用 |
|---|---|---|
| `collaboration.spawn_agent` | 任务名、消息、角色 | 创建子代理并返回标识 |
| `collaboration.list_agents` | 任务路径筛选 | 当前树中的代理状态 |
| `collaboration.wait_agent` | 等待期限 | 等待代理更新或超时 |
| `collaboration.interrupt_agent` | 代理标识 | 请求中断并返回前一状态 |
| `collaboration.followup_task` | 代理标识、消息 | 向现有代理投递任务，空闲时触发回合 |

这些工具属于当前宿主提供的会话能力，Linear Lite 服务端无法凭工具名称取得调用权。LINEAR-LITE-122 需要证明其运行宿主可获得对应控制入口；禁止将 `thread/start` 宣称为原生子代理创建接口。

来源：[App Server](https://learn.chatgpt.com/docs/app-server)、[Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)。

## 3. 命令与停止证据

| 入口 | 输入或输出重点 | 可证明的事实 |
|---|---|---|
| `command/exec` | `command`，可选 `cwd`、`processId`；结果含退出信息 | 该类受控命令的执行结果；命令独立于会话与回合 |
| `command/exec/terminate` | `processId` | 对应命令的终止请求，停止结果需要退出证据 |
| `thread/backgroundTerminals/list` | `threadId`、分页参数 | 协议关联的后台终端列表 |
| `thread/backgroundTerminals/terminate` | `threadId`、`processId` | 对指定后台终端的终止请求 |
| `process/spawn` | `processHandle`、`command`、`cwd` | 在 Codex 受限执行环境之外启动受控进程，属于实验接口 |
| `process/kill`、`process/exited` | `processHandle`；退出通知含退出信息 | 对应受控进程的终止请求与退出事实 |

后台终端和 `process/*` 接口具有实验性约束，本机协议由 `--experimental` 生成；声明存在缺少真实调用成功的含义。`process/*` 的执行边界与 Agent 的命令工具不同，禁止为取得停止能力将任务权限移出原有边界。

以下事实各自不足以证明本卡关联写入进程停止：回合进入 `interrupted`、发送 `SIGTERM`、后台终端列表为空、领取租约到期、某个受控父进程退出。资料缺少全部脱管子进程或后代写入动作被这些接口枚举与控制的保证。

**释放修改范围的前提是宿主提交覆盖本次执行单元的停止证据。** 宿主缺少覆盖证据时，服务端保留冻结范围；领取标识或状态更新无法替代本机进程控制。

来源：[App Server 进程与命令契约](https://learn.chatgpt.com/docs/app-server)。

## 4. 设备、项目目录与批次授权

| 对象 | 集成契约 |
|---|---|
| 设备身份 | 宿主持久保存稳定 `deviceId`，主机名、IP 与路由值作为展示或连接信息 |
| 项目绑定 | 从 Linear Lite 获取认证用户、项目、设备对应的唯一绑定记录 |
| 项目目录 | 绑定的 `project_directory` 为目录事实；宿主使用 `stat`、`realpath` 确认本机目录 |
| 协作资料 | 从项目根目录派生 `docs/`，禁止增加目录配置或任务状态副本 |
| 物理目录互斥 | 宿主拒绝同一规范化物理根目录的第二个并发项目协作运行 |
| 批次授权 | 服务端签发的批次上下文固定用户、项目、绑定、卡片范围及期限；执行请求沿该绑定传递 |
| 授权材料保管 | 宿主保管批次与领取授权材料，禁止写入日志、文档、评论、检查点或模型的公共成果 |

本会话的 Codex 项目目录接口返回仓库路径和 `hostId=local`。`hostId` 是否具有稳定设备身份语义缺少证据；Codex 项目标识与 Linear Lite 项目标识属于不同系统，禁止互换。

现有 Pi 的 `ProjectConfigStore` 以 `projectId` 保存目录，通过 `stat` 与 `realpath` 校验，缺少用户和设备维度。这一目录来源属于 Pi 人工 turn 链；协作运行使用 Linear Lite 绑定，禁止增加备用目录读取路径。

源码：`pi-bridge/src/workspace-config.mjs:29`、`:64`、`:113`；`pi-bridge/src/index.mjs:213`。

## 5. 续租、槽位与恢复调用链

| 阶段 | 宿主动作与服务端事实 |
|---|---|
| 启动 | 读取绑定 → 校验设备与根目录 → 取得批次授权 → 按预算填充执行槽位 |
| 领取 | Agent 查询环境 → 服务端原子领取 → 宿主关联批次、任务、尝试、子代理与命令实例 |
| 执行 | 宿主驱动续租；Agent 修改授权范围并追加检查点；模型长时间推理或工具等待保持租约机制独立 |
| 交付 | 宿主确认本卡执行单元的停止事实，提交输入版本与成果证据，由服务端完成事务推进 |
| 取消或失效 | 请求停止 → 中断代理并终止关联写入动作 → 收集退出证据 → 确认停止 → 释放资源 |
| 宿主失联 | 服务端标记失联并冻结范围，阻止冲突任务领取 |
| 恢复 | 宿主按保存的绑定、批次与尝试定位执行单元，核实本机进程与检查点；证据缺失时维持冻结 |
| 停止条件 | 区分本批完成、预算耗尽、用户停止与业务决策；依赖等待、资源等待和空查询缺少完成语义 |

会话恢复恢复上下文，业务恢复依赖执行记录与本机事实。单凭 `threadId` 或会话历史无法重建进程归属。宿主槽位数量、运行期限和预算来源为本批授权与运行配置，禁止由模型临时猜测。

Goal 与定时任务提供目标推进和计划触发，缺少 Linear Lite 续租及停止证明语义；本地计划运行需要机器和应用运行。本卡没有启用计划任务或启动批量执行。

来源：[Goals](https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex)、[Scheduled tasks](https://learn.chatgpt.com/docs/automations?surface=app)。

## 6. 对 LINEAR-LITE-122 的实施前提

| 能力缺口 | 后续交付要求 |
|---|---|
| 可用宿主控制入口 | 确认宿主版本、权限与连接方式，证明其可以控制目标子代理；声明与运行证据分开保存 |
| 全部关联写入动作的归属与停止覆盖 | 建立本次执行单元与受控命令/后代进程之间的覆盖证据；无法证明时冻结资源 |
| 稳定设备与目录绑定 | 实现设备标识持久化、绑定读取、路径校验和同物理根目录互斥 |
| 宿主续租与授权保管 | 在模型调用之外维持续租，保护批次上下文与领取材料，处理过期授权 |
| 重启恢复 | 持久关联批次、尝试、代理、命令及检查点，恢复后核实残留写入并提交停止确认 |

上述能力属于 LINEAR-LITE-122 的实现与验收前提。LINEAR-LITE-121 完成表示研究材料与缺口结论交付，禁止推断宿主运行链具备生产可用性。

## 7. 本卡验收对应

| 任务卡验收 | 材料位置 |
|---|---|
| 给出可调用入口、输入输出与能力证据 | 第 1～3 节与本机协议声明 JSON，运行能力待证部分具有标记 |
| 区分代理中断与关联进程停止 | 第 3 节，缺失的覆盖证据列入第 6 节 |
| 用户、项目、设备绑定与单一目录路径 | 第 4 节 |
| 覆盖失联、取消、期限与模型占用边界 | 第 5 节 |
| 状态与研究证据一致 | 本卡交付契约核实；宿主实现缺口归后续任务 |

研究材料交付完成；宿主运行接入与停止覆盖证明为后续实施项。
