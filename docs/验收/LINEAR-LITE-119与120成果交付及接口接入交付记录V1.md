# LINEAR-LITE-119 与 120 成果交付及接口接入交付记录 V1

日期：2026-11-10。代码基线：`a452058`。交付形态为工作区改动，保留前置 LINEAR-LITE-114 至 118 及用户的原有改动。

## 119：交付与完成门禁

`TaskCollaborationCompletionService.complete` 接收认证用户、批次、任务、尝试命令、交付证据及宿主停止声明。证据包含完成条件及结果/引用、修改位置、产物引用和稳定项目检查点；只读任务允许空修改列表，其他修改位置限定于持有范围。

输入版本、前置成果版本和公共约定版本从尝试与任务事实附入证据。服务端校验协议；产物存在性、稳定检查点及关联进程停止事实由宿主核验。

完成事务顺序：归属校验 → 幂等回执 → 当前写资格 → 证据与停止声明 → 保存成功尝试/证据/停止确认 → 状态服务推进 done → 父卡汇总 → 保存回执。项目锁和 READ_COMMITTED 事务覆盖这些写入；停止确认时间解除范围占用，历史范围快照保留。

| 验收要求 | 实现与证据 |
| --- | --- |
| 证据、done、释放资源同事务；故障回滚 | 完成服务具有异常回滚契约；DBX 隔离样本在回执保存故障后回滚，任务 in_progress、尝试 running、证据和停止确认 NULL、回执 0。 |
| 普通更新、进度、导入及父卡汇总受门禁 | `TaskStatusService` 对协作任务进入 done 检查当前版本的成功尝试、非空证据及停止确认；父汇总遇协作参与父卡停止传播。导入保持创建人工任务的原用途，导入后的父汇总经过同一服务。 |
| 旧输入、跨绑定、无领取权、缺证据或停止声明遭拒 | 共用执行归属及写资格校验处理批次、绑定、实例、领取摘要、租约、任务版本及前置快照；完成服务校验证据和停止声明。 |
| 输入变化竞争与重复交付 | 项目协调锁确定顺序；原请求回放原结果。done 重开和关闭授权递增输入版本，旧证明失效。SQL 隔离样本覆盖交付后失效、失效后晚到交付。 |
| 人工任务保持原有行为 | 协作参与判定沿用授权、公共引用、依赖与历史执行；普通人工任务的完成和父子汇总回归通过。 |

## 120：共用接口契约

REST 与 MCP 复用领域服务及严格参数解析。用户身份来自认证上下文；调用方参数排除 userId。业务拒绝 `accepted=false` 作为结构化结果返回，REST 的业务数据与 MCP structuredContent 保持相同内容；入口位于领域事务之外，停止意图在返回前提交。

观察会物化过期停止原因，MCP 使用写权限。停止恢复保留过期批次与权限撤销后的专用归属核验，恢复入口处理停止事实，业务写入资格保持原约束。Pi 人工 turn 接口保持原用途。

新接口的未知键、类型及嵌套参数使用固定错误文案，排除原始请求和解析器错误回显。operationId、路径、凭据及自由文本保留原字符串，避免两入口的 trim 或类型强制差异。

## 凭据与字段路径

签发输出与执行输入使用唯一 `batchToken` 字段，领取响应使用 `claimToken`；执行记录响应、成果、检查点和幂等回执排除授权材料。显式持久载荷与 operationId 检查本次请求携带的批次、领取凭据原文，拒绝调用方将这些内容粘贴进业务材料；错误采用固定文案。未知凭据扫描排除在该机制之外。

该检查覆盖领取实例字段、范围扩张、续租、检查点、停止原因、停止声明和交付证据。直接签发与领取响应承载调用方后续认证所需的凭据，对象字符串输出脱敏。

## DBX 隔离样本

数据库验证使用四张 `ll119_verify_20261110_` 前缀的隔离表；执行尝试与操作回执结构从当前 schema.sql 提取，任务和协调表使用测试数据。业务表的数据及结构保持原状。

1. 完成事务写入成功尝试、证据、停止确认、done 和回执后，重复键制造保存故障；回滚后恢复 in_progress/running、证据和停止确认 NULL、回执 0。
2. 会话 A 持协调行锁，会话 B 的 NOWAIT 请求返回 MySQL 3572；A 完成后 B 变更输入，最终任务 todo、当前版本 2、交付版本 1、当前有效证明 0。
3. 输入变更先提交，旧尝试进入 stop_requested；旧版本交付影响 0 行，最终任务 todo、当前版本 3、尝试版本 2、证据和停止确认 NULL。

隔离表清理完成，两条验证会话关闭。该证据覆盖 SQL 锁、回滚与版本顺序；应用事务与接口行为由源码、服务及接口测试核验。

## 验证记录

119 定向门禁覆盖完成服务、状态、普通任务、导入、父卡汇总、输入、凭据排除及前置授权：10 类、86 项通过。120 定向门禁覆盖接口、MCP 分派及输入读取：3 类、68 项通过。

合并门禁 24 类、202 项通过，失败、错误、跳过为 0；差异检查通过。各轮测试存在重叠，数量不相加。构建包含仓库既有 Maven 校验和策略提示；测试中的拒绝日志为预期断言场景。

```sh
rtk proxy mvn -f linear-lite-server/pom.xml -Dexec.skip=true -Dtest=ProjectDeviceBindingServiceTest,CollaborationBatchAuthorizationServiceTest,TaskCollaborationInputServiceTest,TaskCommandServiceTest,TaskImportServiceTest,ProjectLifecycleServiceTest,CollaborationModelMappingTest,StringListJsonTypeHandlerTest,TaskMapperSqlTest,TaskQueryServiceTest,TaskStatusServiceTest,TaskHierarchyCompletionServiceTest,DocumentCollaborationServiceTest,ProjectDocumentCommandServiceTest,CollaborationPathScopeServiceTest,TaskCollaborationClaimServiceTest,TaskExecutionOperationServiceTest,TaskExecutionClaimCredentialServiceTest,TaskExecutionAccessServiceTest,TaskExecutionLifecycleServiceTest,TaskCollaborationCompletionServiceTest,CollaborationCredentialPolicyTest,CollaborationInterfacesTest,McpDispatcherTest test
rtk proxy git diff --check
```

接口证据使用 MockMvc、真实 MCP 分派器/工具目录/共享适配器，以及 mock 领域服务：验证原有 18 项加新增 14 项工具、14 个 REST 路径、认证用户传递、REST data 与 MCP structuredContent 的结果一致、观察的写权限、嵌套类型与未知字段拒绝、匿名请求、畸形 JSON 脱敏错误及领域拒绝原因。领域权限、绑定、租约、版本、范围与幂等由本轮服务门禁覆盖；这些结果不构成应用部署或真实宿主运行证明。

服务契约：[LINEAR-LITE-119成果交付与完成门禁契约V1.md](../开发指引/LINEAR-LITE-119成果交付与完成门禁契约V1.md)、[LINEAR-LITE-120协作REST与MCP接口契约V1.md](../开发指引/LINEAR-LITE-120协作REST与MCP接口契约V1.md)。

应用部署、Git 提交、浏览器 E2E 与真实宿主进程验收排除在本轮结果之外；方案原文和环境配置保持原状。

技术核对确认完成事务、持久证明门禁、来源版本、凭据边界、共用参数契约及停止恢复符合冻结决策。两卡范围内交付完整；应用部署与宿主运行闭环属于后续工作。
