# LINEAR-LITE-117 与 118 领取及生命周期交付记录 V1

日期：2026-11-10。代码基线：`a452058`；交付为工作区改动，保留 LINEAR-LITE-114、115、116 及用户的原有改动。

## 交付范围

117 提供协作环境观察、原子领取、范围互斥及扩张；118 提供续租、检查点、停止请求与停止确认。领取成功创建执行尝试并推进任务状态；租约失联或输入失效产生停止意图，持有范围在停止确认前冻结。确认停止保留范围历史快照，以 `stop_confirmed_at` 解除占用。

REST/MCP 接口、成功交付命令、宿主设备与进程管理属于后续卡片。服务端校验协作协议；设备文件、符号链接、大小写别名、成果存在性及进程停止事实由宿主核验。

## 任务验收对应

| 卡片 | 验收要求 | 实现与证据 |
| --- | --- | --- |
| 117 | 同任务竞争形成一个有效尝试 | 批次授权取得项目行锁，在 RC 事务内检查任务与依赖、创建尝试、保存持有范围、更新任务和回执；单元测试覆盖占用拒绝，DBX 双会话验证锁竞争及获锁后二次判断。 |
| 117 | 重叠文件、父子目录互斥；其他范围并行 | 路径去尾斜线后按段边界比较，`src` 与 `src/a` 冲突，`src` 与 `src2` 分离；范围按绑定判冲突，任务领取按项目判冲突。等价扩张按规范键去重，范围相同则省略 UPDATE。 |
| 117 | 前置绑定、输入版本及资源冻结阻塞 | 共享输入评估返回前置任务、尝试和输入版本；宿主声明具体可用尝试及版本。其他绑定、旧版本、缺少可用声明或冻结范围产生阻塞原因。 |
| 117 | 回滚覆盖尝试、范围及任务状态 | 服务入口使用同一事务；DBX 隔离样本中创建尝试、更新状态及写回执后回滚，结果为尝试 0、回执 0、任务 todo。 |
| 117 | 空结果与完成、依赖、资源、活动分开表达 | 观察返回全部授权任务与逐卡原因；聚合状态包含 READY、DEPENDENCY_WAIT、RESOURCE_WAIT、ACTIVE、COMPLETE、BLOCKED、EMPTY。同绑定资源归属摘要排除批次外检查点及成果正文。 |
| 118 | 过期、旧凭据、旧版本及错误实例拒绝写入 | 共用执行归属服务校验批次、绑定、任务、尝试、宿主、Agent 及领取摘要；写资格校验租约、当前输入、前置快照、授权、任务和停止状态。 |
| 118 | 失联冻结资源；过期尝试可停止恢复 | 观察与执行检查物化过期停止原因；恢复操作核验认证用户、绑定 owner、设备、尝试、实例及领取摘要，批次有效期、租约和成员资格失效不阻断归属认证后的停止确认。 |
| 118 | 停止后的状态符合当前业务事实 | 取消、重复和完成终态保留；授权关闭或成员资格失效时保留退出状态；授权及权限有效的可重试非终态回到 todo、进度归零。 |
| 118 | 幂等回放和旧尝试隔离 | 请求回执按项目、认证用户和 operationId 唯一；请求或操作类型变化产生冲突。检查点追加序号；心跳更新租约。停止回放和旧尝试的迟到确认保持新尝试的任务状态。 |

## 共享机制与技术核对

- `TaskExecutionAccessService` 将归属校验与写资格判断分开。归属校验在回执读取前执行；原请求回放返回原结果，新的写请求校验当前资格。
- 可预期的过期、输入失效及扩张冲突返回结构化拒绝结果，使停止意图与拒绝回执提交；接口层的冲突映射须发生在事务提交后。
- `task_execution_operations` 保存操作请求及原结果，排除批次和领取凭据；任务及执行记录保持当前状态的事实来源。项目删除在停止确认后清理回执。
- 领取凭据采用现有密钥及用途域 HMAC 派生，尝试保存摘要，领取回放重建凭据；请求对象的字符串输出脱敏。
- JSON 请求比较经序列化往返规范化，避免 LongNode 与 IntNode 差异破坏回放。operationId 使用 `utf8mb4_0900_bin` 排序规则，区分大小写与尾空格。
- 首个停止原因及请求时间保留；每次显式请求的原因和结果由回执保存。

新增表定义归档至 `linear-lite-server/src/main/resources/schema.sql`。方案原文和环境配置保持原状。

## 数据库验证

通过 DBX 在 Linear Lite 数据库创建四张 `ll117_verify_20261110_` 前缀的隔离验证表；执行尝试与操作回执表结构来自当前 schema.sql，协调点与任务表使用测试数据。业务表的数据及结构保持原状。

1. 会话 A 持协调行锁，会话 B 的 NOWAIT 领取锁请求返回 MySQL 3572。A 提交尝试与 in_progress 后，B 获锁并复核条件，新增尝试数为 0；最终有效尝试数为 1。
2. 同一事务创建第二个任务尝试、更新任务并写操作回执；回滚后该任务尝试数 0、回执数 0、状态 todo。
3. 检查点 JSON 追加的期望计数从 0 变为 1；旧期望计数的追加影响 0 行。
4. 过期且缺少停止确认的样本占用数为 1；确认停止后占用数为 0，原 `src/a/` 范围及 LEASE_EXPIRED 原因保留。
5. 临时回执表验证 `operation`、`Operation`、`operation ` 形成三条记录。

四张隔离表及临时表清理完成，承载验证数据的会话关闭。DBX 原生事务工具限制 SET 和部分函数，事务样本采用字面量及 NOWAIT；该证据覆盖 SQL 行锁、回滚与存储行为，应用 RC 配置由源码与事务契约测试核验，REST/MCP 与宿主并发运行属于后续验收。

## 服务验证

117 定向门禁 21 项通过；共用回执及领取凭据门禁 9 项通过；118 定向门禁 30 项通过，覆盖本卡 20 项与 117 的 10 项。

合并门禁 20 类、125 项通过，失败、错误、跳过为 0；包含绑定与批次、输入失效、领取、生命周期、普通任务与导入、状态与层级汇总、项目删除及文档版本变更。定向门禁与合并门禁存在重叠，数量不相加。

```sh
rtk proxy mvn -f linear-lite-server/pom.xml -Dexec.skip=true -Dtest=ProjectDeviceBindingServiceTest,CollaborationBatchAuthorizationServiceTest,TaskCollaborationInputServiceTest,TaskCommandServiceTest,TaskImportServiceTest,ProjectLifecycleServiceTest,CollaborationModelMappingTest,StringListJsonTypeHandlerTest,TaskMapperSqlTest,TaskQueryServiceTest,TaskStatusServiceTest,TaskHierarchyCompletionServiceTest,DocumentCollaborationServiceTest,ProjectDocumentCommandServiceTest,CollaborationPathScopeServiceTest,TaskCollaborationClaimServiceTest,TaskExecutionOperationServiceTest,TaskExecutionClaimCredentialServiceTest,TaskExecutionAccessServiceTest,TaskExecutionLifecycleServiceTest test
rtk proxy git diff --check
```

构建成功，差异检查通过。构建输出包含仓库既有的 Maven 校验和策略及弃用提示。技术核对确认路径互斥、幂等回放、归属/写资格分界与停止恢复符合冻结契约。

服务契约：

- [LINEAR-LITE-117就绪查询与原子领取契约V1.md](../开发指引/LINEAR-LITE-117就绪查询与原子领取契约V1.md)
- [LINEAR-LITE-118执行生命周期契约V1.md](../开发指引/LINEAR-LITE-118执行生命周期契约V1.md)

验证范围为领域服务、编译、事务契约及数据库隔离样本。应用部署、Git 提交、浏览器 E2E 与真实宿主运行排除在本轮结果之外。

两卡范围内交付完整，成功交付命令、对外接口及宿主运行闭环属于后续任务。
