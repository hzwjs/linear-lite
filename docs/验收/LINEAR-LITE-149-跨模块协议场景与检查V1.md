# LINEAR-LITE-149 跨模块协议场景与检查 V1

检查对象为 Linear Lite 痕迹协作 V2 工作树，Git 基线为 `10ff318`。真实宿主客户端使用协议 2 和模拟响应，服务端使用领域测试与 MockMvc，网页使用 Vue 组件和 API 模拟测试。

## 场景与证据

| 场景 | 输入 | 期望 | 检查结果 |
| --- | --- | --- | --- |
| 同任务竞争 | 两个批次观察同一任务，其中一个尝试尚未确认停止 | 服务端保留原尝试归属，其他任务按各自状态领取 | 通过，跨绑定同任务保持等待，独立任务仍可领取。[领取服务测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskCollaborationClaimServiceTest.java) |
| 同目录多批次 | 两个批次使用同一项目根，前一批次可能处于待恢复 | 运行记录按批次与代次分开，恢复中的批次不持有目录 | 通过，独立池、运行记录和预算分别验证。[批次驱动测试](../../codex-host/test/batch-driver.test.mjs) |
| 同文件实际冲突 | 两个尝试读取同一版本，文件在下一次操作前发生变化 | 旧版本编辑遭拒，重读后按当前版本执行，记录实际操作路径 | 通过，受管编辑按文件身份与内容验证。[编辑执行测试](../../codex-host/test/executor.test.mjs) |
| 只读交付 | 任务有完成条件与依据，实际改动和成果文件列表为空 | 交付通过正常校验，保存空改动路径 | 通过，宿主提交空改动并沿用停止证明，服务端校验完成证据。[批次驱动测试](../../codex-host/test/batch-driver.test.mjs) · [交付服务测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskCollaborationCompletionServiceTest.java) |
| 首次采用 | 当前尝试读取网页文档和项目材料后提交版本引用 | 采用事实进入尝试记录，原输入版本与租约沿用 | 通过，首次采用后仍可编辑和交付。[上下文测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/CollaborationContextServiceTest.java) · [宿主执行池测试](../../codex-host/test/pool.test.mjs) |
| 关系失效与撤回 | 尝试提交影响领取条件的关系，或发现该关系判断有误 | 关系变化递增输入版本并收口旧尝试，撤回核验原归属和版本 | 通过，实际变更停止对应尝试，停止确认后的受限撤回按当前事实执行。[关系测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskCollaborationDependencyServiceTest.java) · [宿主执行池测试](../../codex-host/test/pool.test.mjs) |
| 缺产物与缺交付 | 一种情况为有效前置交付的产物缺失，另一种为前置有效交付缺失 | 有交付时下游可领取并复核，缺交付时保持依赖等待 | 通过，当前缺失事实进入观察和领取，领取后走读取、复核、目标内修补、采用与交付。[真实客户端模拟协议](../../codex-host/test/cross-protocol.test.mjs) · [领取服务测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskCollaborationClaimServiceTest.java) |
| 复核后版本变化 | 前置材料在采用后变更，或项目物理根在交付前被替换 | 宿主和服务端拒绝旧观察支持的交付 | 通过，交付前重读材料并校验根身份，归属或证据变化触发停止。[宿主执行池测试](../../codex-host/test/pool.test.mjs) · [交付服务测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskCollaborationCompletionServiceTest.java) |
| 授权内返工 | 复核确认缺产物，来源任务在当前批次授权集合中 | 领域服务使来源交付及下游输入失效，旧尝试停止后来源任务可返工 | 通过，返工记录和本批后续领取覆盖。[前置复核测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskPrerequisiteReviewServiceTest.java) · [宿主执行池测试](../../codex-host/test/pool.test.mjs) |
| 批外目标授权 | 批外返工或独立新增目标，用户确认目标及影响 | 批次所有者确认后生成独立批次，精确来源匹配，原批固定集合和预算保留 | 通过，目标创建、精确返工来源、同一授权回执重放及网页确认覆盖。[批次控制测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/CollaborationBatchControlServiceTest.java) · [网页授权测试](../../src/components/CollaborationGoalAuthorization.test.ts) |
| 预算、空查询与恢复 | 重放启动、断线重连、空查询、达到尝试上限或截止时间 | 沿用原服务端预算，空查询保持等待，耗尽预算按预算结束 | 通过，网页保留启动操作标识，服务端按持久预算计数，宿主重连维持截止时间。[网页批次测试](../../src/views/CollaborationExecutionView.test.ts) · [批次驱动测试](../../codex-host/test/batch-driver.test.mjs) · [宿主执行池测试](../../codex-host/test/pool.test.mjs) |
| 停止归属与退出证明 | 接受或领取回执丢失、进程组存活或归属变化 | 旧尝试保留待恢复，退出和服务端事实确认后清理 | 通过，验证未知领取封存、未知进程归属拒绝确认及进程退出后的停止回执。[恢复测试](../../codex-host/test/batch-driver.test.mjs) · [未确认领取恢复测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/UnacknowledgedClaimRecoveryServiceTest.java) |
| 普通任务完成门禁 | 直接更新为完成或进度 100%，导入新完成任务，子任务全部终态 | 协作任务需要当前成功交付，导入新任务和普通父任务汇总沿用业务语义 | 通过，导入只创建新任务，协作父任务在子任务终态后仍核验自己的交付。[任务更新测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskCommandServiceTest.java) · [导入测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskImportServiceTest.java) · [父任务汇总测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/TaskHierarchyCompletionServiceTest.java) |
| 跨项目、绑定和旧代次 | 请求使用其他项目或绑定、撤销授权、旧运行代次或旧协议 | 执行业务遭拒，原归属恢复按当前协议受限处理 | 通过，设备、绑定、项目、代次、授权、签名版本分别校验。[批次授权测试](../../linear-lite-server/src/test/java/com/linearlite/server/service/CollaborationBatchAuthorizationServiceTest.java) · [REST 与 MCP 契约测试](../../linear-lite-server/src/test/java/com/linearlite/server/mcp/CollaborationInterfacesTest.java) |
| 观察响应完整性 | 服务缺少必填事实数组、复核内嵌观察或成果引用，含第二次刷新畸形 | 宿主拒绝观察，避免把未知事实作为空清单，保留批次恢复材料 | 通过，九种异常响应均未启动 Agent 或领取任务。[真实客户端模拟协议](../../codex-host/test/cross-protocol.test.mjs) |

## 字段与旧入口

网页启动、任务详情和恢复以服务端 DTO 的当前字段读取。实际改动路径来自宿主操作，依赖由 Agent 从共享事实发现并记录，文档版本及本机材料摘要分别来自各自的数据源。宿主观察读取 Java `TaskView` 的必填数组，去掉空数组回退。批次详情与任务详情共用协作事实组件。

生产源目录扫描未发现旧写入范围、任务执行开关、旧公共约定字段、资源等待状态、根锁实现或旧快照可用性门槛。旧路径列表用于返回升级反馈。安装器的旧 `roots` 检查限定在识别旧应用的升级或卸载路径，新协议以运行、尝试和进程组记录判断。

## 分层验证

| 层级 | 结果 |
| --- | --- |
| 前端编译与类型 | `npm run build` 通过，包含 `vue-tsc -b` 和 Vite 生产构建 |
| 前端组件、状态与 API | 18 个文件 120 项通过，另有任务状态改为完成时同步进度的定向用例 1 项通过 |
| Java 编译、领域与 HTTP 协议 | JDK 21 下 Maven 定向测试 28 组 258 项通过，包含 MockMvc、权限、任务状态和下载资源 |
| Node 进程与宿主协议 | `node --test --test-timeout=30000 test/*.test.mjs` 在 `codex-host` 下执行，151 项通过 |
| 安装资源与隔离启动 | V3.0.1 DMG、应用签名、26 个包内源码及协议版本通过检查，内置运行时在临时目录和端口启动，Maven 资源复制逐字节匹配 |

扩大网页检查发现两个历史断言，分别为属性栏分组数量和全屏最小高度。两个用例在干净的 `10ff318` 源码归档复现。任务进度用例的侧栏控件选择器改为当前标识，业务断言通过。

## 安装资源与待执行

- [V3.0.1 安装镜像](../交付产物/Linear-Lite协作宿主-macOS-arm64-V3.0.1.dmg) 与 [来源清单](../交付产物/Linear-Lite协作宿主-macOS-arm64-V3.0.1.dmg.json) 对应本轮宿主源码，继续使用协议 2，V3 原镜像保留。新镜像 SHA-256 为 `ec80cf36cb1458e8317fd3b6a8d365b02935daef380fb0f1f4714f73f4ec7de3`。
- 真实网页、真实模型、数据库、安装授权与生产部署待执行。本轮通过的是本地编译、测试及模拟协议检查。包内计划入口返回 `enabled: false`。
- LINEAR-LITE-144 的受管可写命令隔离与执行验证待决，LINEAR-LITE-146 中的命令接入沿用该依赖。本轮并发证据覆盖受管编辑、来源读取和停止事实。[144 阻断记录](LINEAR-LITE-144-项目沙箱阻断记录V1.md)
- 使用、停止与恢复步骤见 [运行指引 V3](../开发指引/LINEAR-LITE-122-Codex宿主使用与运行边界V3.md)。
