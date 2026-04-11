# 后端代码审查报告（结构合理性 / 规范性 / 性能）

- 审查时间：2026-04-11 17:25:19 CST
- 分支与版本：`main` / `35d79d2`
- 审查范围：`linear-lite-server/src/main/java`（100 文件）、`linear-lite-server/src/main/resources`、`linear-lite-server/src/test/java`（10 文件）
- 审查方式：静态代码审查 + 本地测试执行（`rtk mvn -q test`，通过）

## 结论概览

当前后端具备可运行性和基本分层（Controller/Service/Mapper），但在安全性与一致性上存在阻断级问题，优先级应为：

1. 先修复安全问题（SQL 注入、明文密码、越权访问、明文密钥）
2. 再修复事务一致性与并发冲突（`@Transactional` 缺失、任务号生成竞争）
3. 最后处理性能热点（N+1、大对象内存下载）

---

## 核心问题（按严重级别）

### P0-1：项目邀请接口存在 SQL 注入风险

- 位置：
  - `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java:175-177`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java:270-274`
- 现象：`inSql` 拼接邮箱字符串，邮箱校验仅要求“包含 @”，未阻断 `'` 等危险字符。
- 风险：攻击者可构造邮箱输入影响 SQL 条件，导致越权判断失真或数据泄露。
- 建议：
  - 禁止字符串拼接 SQL；改为参数化查询（Mapper `@Select`/Wrapper `eq`）。
  - 邮箱校验统一使用严格 regex（与注册模块一致）。

### P0-2：账号密码明文存储与明文比对

- 位置：
  - `linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java:76-78`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java:141-145`
  - `linear-lite-server/src/main/resources/schema.sql:189-196`
- 现象：登录时直接比较明文密码，注册时直接入库存储明文，且种子数据也为明文。
- 风险：数据库泄露即全量账号泄露，属于高危合规问题。
- 建议：
  - 立即改为 `BCryptPasswordEncoder`（注册 hash，登录 match）。
  - 清理历史明文种子，提供迁移脚本与强制重置策略。

---

### P1-1：任务活动接口可被非项目成员读取（越权）

- 位置：
  - `linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java:89-95`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskActivityService.java:99-136`
- 现象：`/api/tasks/{id}/activities` 未传入当前用户，也未做项目成员校验。
- 风险：任意登录用户只要猜到 `task_key` 即可读取活动流。
- 建议：
  - `TaskController` 读取 `userId` 并传入 service。
  - `TaskActivityService` 复用 `TaskService.getByKeyOrThrow(taskKey, userId)` 做权限前置。

### P1-2：配置文件包含数据库/邮箱明文凭据

- 位置：`linear-lite-server/src/main/resources/application.yml:11-19`
- 现象：默认值里包含真实 DB host/password 与 SMTP password。
- 风险：仓库泄露即密钥泄露，且难以轮转与审计。
- 建议：
  - 仓库移除所有敏感默认值，改为强制环境变量注入。
  - 立刻轮换已暴露凭据并补充密钥管理规范。

### P1-3：跨表写操作缺少事务边界

- 位置：
  - `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java:201-241`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java:311`（`update(taskKey, request, userId)` 方法整体）
- 现象：项目删除与任务更新涉及多表操作，但方法未标记 `@Transactional`。
- 风险：中途失败会产生部分提交，形成脏数据（孤儿评论/活动/标签关系不一致）。
- 建议：
  - 为多表写方法加 `@Transactional(rollbackFor = Exception.class)`。
  - 为删除链路增加失败补偿与幂等重试设计。

---

### P2-1：任务号生成使用 `count + 1`，并发下会冲突

- 位置：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java:134-137`
- 现象：并发创建同项目任务时，多个请求可能得到同一 `task_key`。
- 风险：触发唯一键冲突，用户侧表现为随机失败。
- 建议：
  - 引入项目级 sequence（独立表或数据库原子计数）。
  - 或以 `MAX(id)` 不可靠方案替换为事务内锁定计数行。

### P2-2：评论提及与导入路径存在明显 N+1 / 高频数据库往返

- 位置：
  - `TaskCommentService.create`：`isProjectMember` 循环校验 `mentionIds`（`97-100`, `172-177`）
  - `TaskService.importTasks`：每行 `insert` 后 `selectById`（`247`）
- 影响：大批量评论提及、任务导入（最多 800）时 DB 压力明显上升。
- 建议：
  - 批量查项目成员一次性构建 `Set<Long>` 校验 mention。
  - 导入路径减少回查；必要字段由应用层组装或批量回读。

### P2-3：附件下载全量读入内存，缺乏流式处理

- 位置：
  - `linear-lite-server/src/main/java/com/linearlite/server/service/S3R2StorageClient.java:37-39`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskAttachmentService.java:79-83`
- 现象：`readAllBytes()` + `ResponseEntity<byte[]>`。
- 风险：并发下载大文件时增加堆内存峰值，放大 OOM 风险。
- 建议：
  - 改为流式响应（`InputStreamResource` / `StreamingResponseBody`）。
  - 添加下载大小上限与超时策略。

---

## 结构合理性评估

- 优点：
  - 分层清晰（Controller/Service/Mapper），职责边界基本可读。
  - 异常体系统一（`GlobalExceptionHandler` + 业务异常类型）。
- 主要结构债务：
  - 关键权限校验分散在各 service，存在漏检（活动流即案例）。
  - 超大类 `TaskService`（832 行）承担过多职责（查询、导入、状态联动、收藏、活动触发、标签编排），维护成本高。
- 建议落地：
  - 抽取 `TaskPermissionGuard`、`TaskMutationService`、`TaskQueryService`。
  - 统一“先鉴权后业务”模板，避免 controller/service 双方都可漏检。

## 代码规范性评估

- 优点：
  - 命名风格总体一致，核心方法有注释。
  - 对 `null` 与参数边界有基础校验。
- 问题：
  - 同项目校验策略不一致（部分严格 regex，部分仅 contains）。
  - 存在 SQL 字符串拼接和敏感信息硬编码，违反安全编码规范。
  - 缺少 Bean Validation（`@Valid` + DTO 约束），大量手写校验分散在 service。

## 性能优化评估

- 已做得不错：
  - 常见列表接口有限制（如通知/活动 `limit` 上限）。
  - `schema.sql` 中已为统计查询增加了复合索引。
- 当前瓶颈：
  - N+1 校验与逐条写回查。
  - 删除与下载链路缺少“规模化”设计（批处理/流式）。

---

## 修复优先级建议（两周内）

1. **D1-D2（安全止血）**
   - 修复 SQL 注入、越权读取、明文配置、密码哈希。
2. **D3-D5（一致性）**
   - 为跨表写路径补事务，修复 `task_key` 并发策略。
3. **D6-D10（性能与重构）**
   - 批量化 mention/导入查询，附件下载改流式，拆分 `TaskService`。

## 审计证据

- 关键审查文件：
  - `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskActivityService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`
  - `linear-lite-server/src/main/resources/application.yml`
  - `linear-lite-server/src/main/resources/schema.sql`
- 验证命令：
  - `rtk mvn -q test`（通过）

---

## 复审结论（2026-04-11 结构债二次收敛）

- 复审时间：2026-04-11
- 复审范围：
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommandService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskQueryService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskImportService.java`
  - `linear-lite-server/src/main/java/com/linearlite/server/service/TaskSequenceService.java`
  - 对应测试：`TaskServiceTest`、`TaskCommandServiceTest`、`TaskImportServiceTest`

### 复审摘要

- `TaskCommandService` 已承接 create/update/favorite 命令职责，不再是薄委托。
- `TaskQueryService` 负责列表/详情/收藏列表与 enrich 读模型拼装。
- `TaskImportService` 独立承接导入校验与导入写入流程。
- `TaskService` 收敛为兼容门面 + 状态联动规则函数，超大类职责拆分完成。
- `task_key` 序号逻辑已在 `TaskSequenceService` 独立维护。

### 复审验证

- 命令：`rtk mvn -q -f linear-lite-server/pom.xml test`
- 结果：通过（含新增拆分测试）。
