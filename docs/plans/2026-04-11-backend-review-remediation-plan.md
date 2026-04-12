# 后端评审问题整改 实现计划

> **给执行者：** 必须使用 `superpowers:executing-plans`（或当前会话逐步执行）按任务顺序完成；每步完成后运行验证命令再进入下一步。

**目标：** 按 `docs/plans/2026-04-11-backend-review-remediation-design.md` 与 `docs/reviews/backend-code-review-2026-04-11.md`，在单分支内完成 P0～P2 及 `TaskPermissionGuard` + `TaskService` 拆分，一次合并入主线。

**方案概述：** 依赖顺序为：共享邮箱/密码工具与 DDL → 项目与认证安全修复 → 任务读路径越权与配置脱敏 → 事务与序号表 → 性能与流式下载 → 最后抽取 Guard 并拆分 `TaskService`，必要时保留薄门面。

**技术栈：** Spring Boot 3.2、MyBatis-Plus 3.5、MySQL 8、AWS SDK v2 S3、`spring-security-crypto`（仅 BCrypt，不引入完整 Spring Security Filter）。

---

### 任务 1：依赖与密码 Bean

**涉及文件：**
- 修改：`linear-lite-server/pom.xml`

**步骤 1：** 在 `dependencies` 中增加：

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

（版本由 Spring Boot BOM 管理。）

**步骤 2：** 新增配置类 `linear-lite-server/src/main/java/com/linearlite/server/config/PasswordEncoderConfig.java`，暴露 `@Bean BCryptPasswordEncoder`（使用默认强度即可）。

**步骤 3：** 运行 `rtk mvn -q -f linear-lite-server/pom.xml -DskipTests compile`  
**预期：** BUILD SUCCESS。

**步骤 4：** 提交（示例信息）：`chore(server): 引入 BCrypt 依赖与 PasswordEncoder Bean`

---

### 任务 2：统一邮箱校验工具

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/util/EmailNormalization.java`（名称可按项目惯例调整）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java`（`normalizeEmail` 改为调用工具类中的同一 `Pattern`）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`（`requireEmail` 改为调用工具类，删除弱校验）

**步骤 1：** 将 `AuthService` 中 `EMAIL_PATTERN` 移至工具类，提供 `normalizeAndValidate(String email)`：trim、lower、regex、失败抛与现有一致的 `IllegalArgumentException` 文案或统一中文/英文（与 `AuthService` 保持用户可见行为一致）。

**步骤 2：** `AuthService.normalizeEmail` 委托工具类。

**步骤 3：** `ProjectService.requireEmail` 改为调用工具类（邀请等路径统一严格邮箱）。

**步骤 4：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 全部通过。

**步骤 5：** 提交：`refactor(server): 统一邮箱规范化与格式校验`

---

### 任务 3：消除 `ProjectService` 中的不安全 `inSql`

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`

**步骤 1：** `invite`：删除拼接邮箱的 `inSql`。改为：用 `userMapper.selectOne` + `eq(User::getEmail, normalizedEmail)`（或 `selectCount`）得到用户 id；若 id 非空，再 `projectMemberMapper.selectCount` + `eq(ProjectMember::getProjectId)` + `eq(ProjectMember::getUserId, userId)` 判断「已在项目中」。

**步骤 2：** `list` / `listMembers`：将 `"SELECT ... WHERE user_id = " + currentUserId` 与 `project_id = " + projectId` 的 `inSql` 改为 **参数化** 写法。优先使用 MyBatis-Plus 的 `exists` / `nested` / `in` 子查询 API；若 API 不便，可用 **独立 Mapper 方法** `@Select("... WHERE user_id = #{userId}")` 返回 id 列表再 `.in(Project::getId, ids)`。目标：**禁止**在 SQL 字符串中拼接任何外部输入（含 Long 也应占位符化以统一风格）。
若采用“先查 ids 再 `.in(...)`”路径，必须处理空集合：`ids.isEmpty()` 时直接返回空结果，禁止生成 `IN ()`。

**步骤 3：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 4：** 提交：`fix(server): 项目列表/成员/邀请路径移除字符串拼接 SQL`

---

### 任务 4：`AuthService` 密码哈希与登录迁移

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/AuthService.java`（构造注入 `BCryptPasswordEncoder`）
- 修改：`linear-lite-server/src/main/resources/schema.sql`（种子用户 `password` 列为 bcrypt 哈希）
- 可选：`linear-lite-server/README.md` 或仓库根 `README.md` 说明如何用 `BCryptPasswordEncoder` 生成本地种子密码

**步骤 1：** `register`：`user.setPassword(passwordEncoder.encode(password))`。

**步骤 2：** `login`：在 `findUserByIdentity` 后，若 `user.getPassword()` **看起来像 bcrypt**（例如以 `"$2"` 开头），则 `passwordEncoder.matches(normalizedPassword, user.getPassword())`；否则使用 `normalizedPassword.equals(user.getPassword())`，若相等则 `userMapper.updateById` 写回 `encode` 后的密码（就地迁移）。

**步骤 3：** 更新 `schema.sql` 中 INSERT 用户的密码为已知明文（如 `password123`）对应的 **bcrypt 哈希**（可用一次性小工具或单元测试 main 打印，**勿**将生成脚本长期留在生产代码 unless 项目接受）。

**步骤 4：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过；若有认证相关测试，覆盖 bcrypt 登录。

**步骤 5：** 提交：`fix(server): 使用 BCrypt 存储密码并支持明文就地迁移`

---

### 任务 5：任务活动流鉴权（P1-1）

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/controller/TaskController.java`（`listActivities` 增加 `HttpServletRequest`，取 `userId`）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskActivityService.java`（`listByTaskKey` 增加 `userId`，开头调用 `taskService.getByKeyOrThrow(taskKey, userId)` 或后续 Guard）

**步骤 1：** 改签名与调用链，保证未登录与无权限行为与 `getByKeyOrThrow` 一致。

**步骤 2：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 3：** 提交：`fix(server): 任务活动列表按项目成员鉴权`

---

### 任务 6：配置脱敏（P1-2）

**涉及文件：**
- 修改：`linear-lite-server/src/main/resources/application.yml`
- 修改：任选 `README.md` / `linear-lite-server/README.md` / `docs/` 下部署说明（列出 `MYSQL_*`、`MAIL_*` 必填）

**步骤 1：** 删除 YAML 中 **真实** host/IP、用户名、密码、邮箱口令默认值；改为空字符串、`changeme` 或 **无默认值**（仅 `${VAR}`），确保克隆仓库不会自带泄露凭据。

**步骤 2：** 文档中说明本地复制 `.env.properties` 或 export 变量。

**步骤 3：** 提交：`fix(server): 移除 application 默认敏感凭据并补充环境变量说明`

---

### 任务 7：跨表写事务（P1-3）

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`（`delete` 方法或类级 `@Transactional`）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`（`update(String, UpdateTaskRequest, Long)` 若尚未事务化则补上）

**步骤 1：** 为 `ProjectService.delete` 增加 `@Transactional(rollbackFor = Exception.class)`。若同类内存在「自调用」导致事务不生效，将删除逻辑抽到 **独立 Spring Bean**（如 `ProjectDeletionService`）或确保仅由外部通过代理调用（实现时二选一并验证）。

**步骤 2：** 确认 `update(..., userId)` 全路径在同一事务内（含标签、活动等副作用）。

**步骤 3：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 4：** 提交：`fix(server): 项目删除与任务更新跨表写加事务`

---

### 任务 8：项目级任务序号表（P2-1）

**涉及文件：**
- 修改：`linear-lite-server/src/main/resources/schema.sql`（新建 `project_task_seq`，**无 FOREIGN KEY**）
- 新增：Entity + Mapper（如 `ProjectTaskSeq`、`ProjectTaskSeqMapper`）
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java` 的 `create` 与 `importTasks` 中生成 `task_key` 的逻辑

**步骤 1：** DDL 示例（按项目命名微调）：

```sql
CREATE TABLE project_task_seq (
  project_id BIGINT NOT NULL PRIMARY KEY,
  next_number BIGINT NOT NULL
) ...;
```

**步骤 2：** 在 **同一 `@Transactional` 方法**内：`SELECT ... FROM project_task_seq WHERE project_id = ? FOR UPDATE`；若无行则 `INSERT`；读取 `next_number` 作为当前序号，批量导入时 **预留** `rows.size()` 个连续号或循环内每次 `+1` 更新（须在同事务内完成）。

**步骤 3：** 移除对 `count + 1` / `count + index + 1` 的依赖（与 seq 对齐，注意已有项目首次迁移：可在首次取号时 `INSERT` 并初始化 `next_number` 为当前 `MAX` 序号解析值 + 1，若实现成本高可先文档要求手工 backfill，优先保证新数据正确）。

**步骤 4（迁移门禁，必须）：**
- 提供并执行 backfill SQL：按项目把 `project_task_seq.next_number` 初始化为 `max(task_key 序号)+1`。
- 提供并执行校验 SQL：检查 `project_task_seq` 覆盖全部项目、`next_number` 大于现存最大序号、`task_key` 无重复。
- 未完成 backfill + 校验前，不得在该环境开启新版本写流量。

**步骤 5：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 6：** 提交：`fix(server): 项目维度原子任务序号，避免并发 task_key 冲突`

---

### 任务 9：评论 mention 与导入 N+1（P2-2）

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskCommentService.java`
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskService.java`（或拆分后的 `TaskImportService`）

**步骤 1：** `TaskCommentService.create`：对 `mentionIds` 使用 **一次** `projectMemberMapper.selectList`（`project_id` + `user_id IN (...)`）得到 `Set<Long>`，校验 mention 全集。

**步骤 2：** 导入：第一轮 insert 收集新生成 `id`（MyBatis `useGeneratedKeys` 批量或循环后统一 `selectBatchIds`），避免每行 `selectById`。

**步骤 3：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 4：** 提交：`perf(server): 评论提及与任务导入减少数据库往返`

---

### 任务 10：附件下载流式（P2-3）

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/S3R2StorageClient.java`
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskAttachmentService.java`
- 可能修改：`linear-lite-server/src/main/java/com/linearlite/server/controller/...`（若返回类型从 `byte[]` 改为 `Resource`）

**步骤 1：** 统一采用 `StreamingResponseBody`（或等价单一范式）输出下载流，不允许保留“多种可选关闭路径”。
在实现中明确关闭责任：`TaskAttachmentService` 返回可关闭流对象，Controller 侧在流式回调中 `try-with-resources` 读取并写出，确保异常路径也关闭连接。

**步骤 2：** Controller 返回流式 body；若可取得 `Content-Length` 则设置；增加可配置 **最大字节** 上限，超限抛业务异常并由 `GlobalExceptionHandler` 映射。

**步骤 3：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 4：** 提交：`perf(server): 附件下载改为流式并限制最大体积`

---

### 任务 11：`TaskPermissionGuard` 与 `TaskService` 拆分

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/TaskPermissionGuard.java`（或 `security` 包下）
- 新增：`TaskQueryService.java`、`TaskCommandService.java`、`TaskImportService.java`
- 修改：所有注入 `TaskService` 的 Controller/Service，按调用改为注入子服务或门面
- 修改：`TaskService.java`：薄委托或删除（以调用面最小为准）

**步骤 1：** 将 `requireProjectMember`、`getByKeyOrThrow`（及仅用于权限的 `requireTaskByKey` 逻辑）迁入 `TaskPermissionGuard`，`TaskService` 原方法改为委托 Guard + 子服务。

**步骤 2：** 按设计文档划分：`TaskQueryService` / `TaskCommandService` / `TaskImportService`，移动对应方法与 **仅被这些方法使用的私有方法**，注意循环依赖（可通过构造函数注入顺序或接口拆分解决）。

**步骤 3：** `TaskActivityService`、`TaskCommentService` 等对任务的鉴权统一走 Guard。

**步骤 4：** `rtk mvn -q -f linear-lite-server/pom.xml test`  
**预期：** 通过。

**步骤 5：** 提交：`refactor(server): 抽取 TaskPermissionGuard 并拆分 Task 查询/命令/导入服务`

---

### 任务 12：全量验证与单分支合入准备

**步骤 1（Gate A）：** 完成 P0 + P1 后先执行一次 `rtk mvn -q -f linear-lite-server/pom.xml test`，并完成手工安全回归（越权、注入、密码、配置、事务）。
Gate A 满足后允许优先合入/发布，不必等待重构任务。

**步骤 2（Gate B）：** P2 + 结构重构完成后再次执行 `rtk mvn -q -f linear-lite-server/pom.xml test`。  
**预期：** BUILD SUCCESS，测试全通过。

**步骤 3：** 手工清单（文档勾选项即可）：非成员 `GET /api/tasks/{key}/activities` 401/403；邀请恶意邮箱；两并发 POST 创建同项目任务 key 不重复；附件大文件下载内存不明显飙升（可抽样观察）；序号 backfill 校验脚本在目标环境通过。

**步骤 4：** 按团队流程发起 merge（可单次合并或按 Gate A/Gate B 分批合并）；合入说明中引用本计划与设计文档。

---

## 执行方式交接

**计划已保存到 `docs/plans/2026-04-11-backend-review-remediation-plan.md`。有两种执行方式：**

1. **当前会话逐步执行** — 按任务 1→12 顺序改代码、跑测试、提交，任务之间本地 review。  
2. **独立会话 + `executing-plans`** — 在新 worktree/新会话中加载本计划，按检查点汇报。

若使用子代理执行，可配合 `superpowers:subagent-driven-development` 将每个任务交给独立 subagent，并在任务间做审查。

---

## 参考

- 设计：`docs/plans/2026-04-11-backend-review-remediation-design.md`
- 评审：`docs/reviews/backend-code-review-2026-04-11.md`

---

## 执行记录（2026-04-11 更新）

- 任务 8（序号表门禁）迁移/校验 SQL 已归档到：
  - `linear-lite-server/src/main/resources/schema.sql`（“归档：project_task_seq 迁移与校验 SQL”段）
- 任务 10（流式下载）补充空 `fileSize` 防御，避免空值下载路径风险。
- 任务 11（结构债）完成第二轮收敛：
  - `TaskCommandService` 承接命令职责（create/update/favorite）。
  - `TaskQueryService` 承接查询职责（list/get/favorites/enrich）。
  - `TaskImportService` 承接导入职责。
  - `TaskService` 收敛为兼容门面，保留最小职责。
- 验证：`rtk mvn -q -f linear-lite-server/pom.xml test` 通过。
