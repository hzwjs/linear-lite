# LINEAR-LITE-115 与 116 绑定及输入服务交付记录 V1

日期：2026-11-10。任务：LINEAR-LITE-115、LINEAR-LITE-116。代码基线：`a452058`；交付形态为工作区改动，包含前置 LINEAR-LITE-114 的数据模型改动。

## 交付结论

两张任务卡的领域服务、现有写入链路接入、失败契约与定向验证完成。后端 14 类、86 项测试通过，失败、错误、跳过为 0；前端 2 项测试及类型检查通过。技术核对确认项目锁、输入失效传播与批次授权的事务边界符合约定。

领取、续租、停止确认、交付接口、新增 REST/MCP 接口及宿主接入属于后续任务；本轮没有部署、Git 提交或浏览器 E2E。数据库验证使用 DBX 会话临时表，没有修改业务表。

## LINEAR-LITE-115 验收对应

| 卡片要求 | 实现及验收证据 |
| --- | --- |
| 用户、项目、设备绑定；认证身份不可替代 | `ProjectDeviceBindingService` 从认证上下文读取用户，核验成员权限和绑定归属；`ProjectDeviceBindingServiceTest` 覆盖他人绑定、非成员、设备和目录声明失配。 |
| 固定批次、绑定、设备、任务范围与有效期 | `CollaborationBatchAuthorizationService` 签发签名上下文；`observeBatch` 用于批次观察，`authorizeTask` 要求目标任务 ID；核验任务的项目归属、有效期、绑定版本和范围。测试覆盖篡改、过期、失配设备、批次外任务及认证身份。 |
| 项目目录为目录事实；docs 从项目目录派生 | 绑定保存宿主声明的规范化项目根目录，服务校验目录格式；`docs/` 从根目录派生。宿主对本机目录、符号链接及持久设备身份的核验留给宿主接入任务。 |
| 停止确认前禁止改目录或删除 | 存在 `stop_confirmed_at IS NULL` 的尝试时冻结变更，包含租约过期或失败但缺少停止确认的尝试；停止确认后允许变更。软删除保留绑定 ID，目录变化、删除、恢复递增 `revision`，防止 A→B→A 恢复旧授权。 |
| 签名材料排除日志、文档与检查点 | 令牌对象字符串输出脱敏，失败信息采用固定文本；批次令牌与登录 JWT 的用途、主体分离，真实 `JwtAuthFilter` 拒绝将批次令牌用作登录身份。交付材料不包含令牌或领取凭据。 |

本卡两个服务测试类合计 16 项通过。服务契约见 [LINEAR-LITE-115项目绑定与批次授权契约V1.md](../开发指引/LINEAR-LITE-115项目绑定与批次授权契约V1.md)。

后续领取与回写服务须在同一个 READ_COMMITTED 事务中完成 `authorizeTask`、尝试所属批次核验及数据变更，使项目锁覆盖写入；本卡提供授权入口，不包含领取和回写接口。

## LINEAR-LITE-116 验收对应

| 卡片要求 | 实现及验收证据 |
| --- | --- |
| 管理协作授权、计划范围、公共引用及依赖 | `TaskCollaborationInputService` 管理输入；依赖表为依赖事实来源，限制同项目叶子卡，拒绝自依赖和环，替换时去重。`TaskCollaborationInputServiceTest` 覆盖这些条件及依赖替换。 |
| 输入变化递增版本，当前及下游成果失效 | 需求、协作输入、依赖、公共文档版本变化通过项目锁和集合遍历传播；每个受影响任务递增一次版本，`done` 回到 `todo`、进度归零。`requireCurrentInputs` 拒绝旧版本。 |
| 输入失效触发停止且保留资源 | 活动尝试写入 `stop_requested_at`，保留持有范围、原输入版本和停止确认事实；停止确认不属于本卡。 |
| 前置成功必须有执行证据 | 校验叶子卡、`done`、同绑定成功尝试、当前输入版本、停止确认及证据；`canceled`、`duplicate`、过期成果及父卡汇总不构成成功证明。 |
| 普通入口与公共文档变化没有旁路 | `TaskCommandService`、`TaskImportService` 的需求/状态/父节点变更接入领域事件；文档保存、版本恢复、归档和恢复在原事务内发布同步事件。公共引用沿 `tasks.public_context_refs` → DTO → `ApiTask.publicContextRefs` → domain 传递。 |
| 关闭授权、取消或权限撤销触发停止 | 关闭授权和取消按输入失效规则处理；现有项目删除路径先提交停止意图，等待停止确认后清理依赖、尝试和绑定。项目没有成员移除入口，本轮不新增该入口。 |

普通人工任务没有协作授权、公共引用、任一方向依赖及历史尝试时，修改标题不撤销其人工完成状态；协作参与历史和引用纳入父节点保护及失效判断。

契约见 [LINEAR-LITE-116痕迹协作输入与依赖契约V1.md](../开发指引/LINEAR-LITE-116痕迹协作输入与依赖契约V1.md)。

## 事务核对

项目行锁为协调入口。文档协调写事务、文档标题事务模板和项目删除事务模板采用 READ_COMMITTED，避免等待项目锁后沿旧快照遗漏其他事务提交的依赖；停止预检采用 REQUIRES_NEW + READ_COMMITTED，冲突响应发生在停止意图提交后。只读协作会话保留原隔离级别，全库配置没有改动。

架构技术核对确认该旧快照问题消除；新增隔离契约测试覆盖相关写入口。该结论来自源码、差异及定向测试，没有进行多进程数据库并发演练。

## 验证记录

首轮后端验证 84 项通过，包含绑定、授权、输入失效、任务写入、导入、项目删除、模型映射、JSON 处理、查询、状态、层级汇总及文档协作：

```sh
rtk proxy mvn -f linear-lite-server/pom.xml -Dexec.skip=true -Dtest=ProjectDeviceBindingServiceTest,CollaborationBatchAuthorizationServiceTest,TaskCollaborationInputServiceTest,TaskCommandServiceTest,TaskImportServiceTest,ProjectLifecycleServiceTest,CollaborationModelMappingTest,StringListJsonTypeHandlerTest,TaskMapperSqlTest,TaskQueryServiceTest,TaskStatusServiceTest,TaskHierarchyCompletionServiceTest,DocumentCollaborationServiceTest,ProjectDocumentCommandServiceTest test
```

隔离级别修正后，四类受影响测试共 31 项通过：

```sh
rtk proxy mvn -f linear-lite-server/pom.xml -Dexec.skip=true -Dtest=DocumentCollaborationServiceTest,ProjectLifecycleServiceTest,TaskCollaborationInputServiceTest,ProjectDocumentCommandServiceTest test
```

按每类最后一次 Surefire 报告汇总，14 类共 86 项通过；84 与 31 存在重叠，不相加。前端验证：

```sh
rtk proxy ./node_modules/.bin/vitest run src/services/api/task.test.ts --exclude 'docs/tmp/**'
rtk proxy ./node_modules/.bin/vue-tsc -b --pretty false
rtk proxy git diff --check
```

前端测试 2 项通过，类型检查和差异空白检查通过。后端构建输出包含仓库既有的校验和及弃用提示，没有测试失败。

## DBX 临时表验证

DBX 会话在 MySQL 上使用四张临时表，覆盖新增建表定义及旧表结构的增量 ALTER：绑定 `revision` 默认 1、`deleted_at` 默认 NULL，公共引用默认为空 JSON 数组。目录 A→B→A 后绑定 ID 保持、版本为 3；软删除及恢复后版本为 5。文档引用 JSON 的文档 ID 查询和版本读取符合预期。

四张临时表删除、会话关闭。增量定义归档在 `linear-lite-server/src/main/resources/schema.sql`；业务数据库没有执行这些结构变更。

## 交付边界

- 保留 LINEAR-LITE-114 及用户工作区改动；方案原文、环境配置没有改动。
- 本轮接口调用为领域服务和现有任务/文档链路；新增对外接口属于 LINEAR-LITE-120，宿主接入属于后续宿主任务。
- 服务测试、数据库临时结构核验和前端类型检查构成本轮证据；没有应用部署、真实宿主运行或浏览器 E2E 结果。
- 两张卡范围内交付完整；运行闭环和部署属于后续工作。
