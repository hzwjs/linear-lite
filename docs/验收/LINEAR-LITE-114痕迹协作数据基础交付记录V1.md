# LINEAR-LITE-114 痕迹协作数据基础交付记录 V1

日期：2026-11-10。代码基线：`a452058`。范围：LINEAR-LITE-114 数据基础。

## 1. 交付内容

| 对象 | 交付结果 |
|---|---|
| 本机项目绑定 | `project_device_bindings` 保存用户、项目、设备与唯一项目根目录，三元组唯一约束 |
| 任务输入 | `tasks.agent_execution_enabled`、`input_revision`、`write_scopes`，数据库默认值为 `false`、`1`、`[]` |
| 前置依赖 | `task_dependencies` 保存任务与前置任务的去重关系，父子关系保持原有语义 |
| 执行尝试 | `task_execution_attempts` 保存批次、绑定、执行实例、输入与成果版本、租约、范围、检查点、结果及停止事实 |
| JSON 映射 | 路径使用严格字符串数组处理器，对象使用 `JsonNode`，自定义查询配置显式结果映射 |
| 字段路径 | 数据表 → 任务实体或列表 DTO → API 类型 → `toTask` → 任务领域类型，三个新增字段缺少回退路径 |
| 授权材料 | 领取标识原文排除于持久化实体，摘要排除于实体 JSON 与公共响应 |
| 归档材料 | `schema.sql` 包含全量结构与增量列定义；[数据契约](../开发指引/LINEAR-LITE-114痕迹协作数据契约V1.md)定义字段与后续服务责任 |

## 2. 数据库证据

数据库操作通过 DBX MCP 完成，使用 MySQL `8.0.45` 的固定会话与临时表；临时表名称具有 `ll114_` 前缀，业务表保持原状。

| 检查 | 结果 |
|---|---|
| 全量 DDL | 从本次 `schema.sql` 提取任务、绑定、依赖和尝试定义，改为临时表后创建成功 |
| 增量列定义 | 在缺少新字段的临时旧任务表执行三列 ALTER 成功 |
| 全量与增量默认值 | 两条路径读取结果为执行授权 `0`、输入版本 `1`、范围长度 `0` |
| 绑定唯一性 | 同用户跨设备、同设备跨项目保存三条记录；重复用户、项目、设备组合返回 `1062` |
| 依赖去重 | 首条依赖保存成功，重复边返回 `1062` |
| 领取摘要去重 | 重复摘要返回 `1062`，禁止同领取材料对应多个尝试 |
| JSON 范围 | 路径数组和中文文档路径保存并读取成功 |
| 检查点追加 | 预期数量为 `0` 的首条追加影响 `1` 行；重复旧数量追加影响 `0` 行；预期数量为 `1` 的下一条影响 `1` 行 |
| 历史保持 | 两条检查点顺序保留；到期租约与停止请求保留持有范围；新尝试保留前次输入、结果和停止确认 |
| 清理 | 六张本次临时表删除，固定会话关闭 |

上述检查证明数据定义与持久化表达。领取授权、租约校验、范围释放及请求幂等由后续领域服务实现，禁止将临时表写入模拟当作完整协作业务验证。

## 3. 代码验证

后端定向检查命令：

```sh
rtk proxy mvn -f linear-lite-server/pom.xml -Dexec.skip=true -Dtest=StringListJsonTypeHandlerTest,CollaborationModelMappingTest,TaskMapperSqlTest,TaskQueryServiceTest,TaskCommandServiceTest,TaskStatusServiceTest,TaskImportServiceTest test
rtk proxy mvn -f linear-lite-server/pom.xml -Dexec.skip=true -Dtest=TaskHierarchyCompletionServiceTest test
```

八个测试类共 `39` 项通过，失败、错误与跳过数量为 `0`。验证覆盖路径数组拒绝非字符串元素、嵌套 JSON 还原、公共响应授权摘要隔离、详情与列表字段映射，以及任务查询、创建更新、状态、导入和父子汇总的受影响行为。

前端定向检查：

```sh
rtk proxy ./node_modules/.bin/vitest run src/services/api/task.test.ts --exclude 'docs/tmp/**'
rtk proxy ./node_modules/.bin/vue-tsc -b --pretty false
```

本次源文件 `2` 项测试通过，前端类型检查通过。Vitest 使用路径排除历史副本，历史副本结果排除于本次覆盖统计。`git diff --check` 通过。

## 4. 验收边界

LINEAR-LITE-114 的数据基础、字段契约和受影响验证满足卡片范围。绑定服务、输入失效、领取、生命周期、交付门禁、REST/MCP、宿主与界面交互归后续任务。

业务库 DDL 部署、应用部署、定时启用和批量执行排除于本次交付；E2E 属于本次用户授权范围之外。原方案、环境配置及用户工作区材料保持原状，Git 提交排除于本轮操作。

数据基础交付完成；后续业务实现与部署为待办项。
