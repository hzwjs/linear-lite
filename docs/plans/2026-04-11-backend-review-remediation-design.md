# 后端评审问题整改 — 设计说明

- 关联评审：`docs/reviews/backend-code-review-2026-04-11.md`
- 范围：**P0～P2 + 结构债**（权限守卫抽象、`TaskService` 拆分）
- 交付方式：**方案 1 — 单次大爆炸合并**（单功能分支完成全部变更后一次合入主线）
- 状态：已认可（2026-04-11）

## 1. 交付与分支策略

- **对外**：一个 merge（或 squash 后一条主线提交），与「串行多 PR」相对，diff 大、评审与 bisect 粒度粗，回滚为整块。
- **对内**：同一分支上仍按 **P0 → P1 → P2 → 结构重构** 顺序开发与提交（可多 commit），降低联调难度；合入前是否 squash 由仓库惯例决定。

## 2. P0 / P1

### 2.1 P0-1 邀请 SQL 注入

- 移除 `inSql(..., "SELECT id FROM users WHERE email = '" + normalizedEmail + "'")` 等字符串拼接 SQL。
- 改为参数化路径：`LambdaQueryWrapper` / `eq(User::getEmail, email)` 或 Mapper `@Select` 绑定参数；再与项目成员关系组合判断。
- 邮箱校验与注册侧 **统一严格规则**（共享常量或工具类），禁止仅靠 `contains("@")`。

### 2.2 P0-2 密码哈希

- 使用 **`BCryptPasswordEncoder`**：注册 `encode`，登录 `matches`。
- **历史明文**：若存库值非 bcrypt 形态（例如不以 `$2` 前缀），登录仍允许明文比对一次；**成功后**写回 bcrypt，完成就地迁移。新用户仅 bcrypt。
- **`schema.sql` 种子**：使用 bcrypt 占位哈希；文档说明本地如何生成测试密码，不提交真实生产口令。

### 2.3 P1-1 活动流越权

- `TaskController` `GET .../activities` 读取 `userId` 并传入 service。
- `TaskActivityService` 在列活动前调用 **`getByKeyOrThrow(taskKey, userId)`**（或后续 Guard 等价能力），与详情接口一致鉴权。

### 2.4 P1-2 配置敏感信息

- `application.yml` 去除真实 DB/SMTP 凭据默认；占位或 `${ENV_VAR:}`。
- 部署文档列出必填环境变量；已出现在仓库的凭据需 **轮换**（运维 checklist）。

### 2.5 P1-3 事务

- `ProjectService.delete` 多表删除：`@Transactional(rollbackFor = Exception.class)`，注意 Spring 代理与自调用。
- `TaskService.update(taskKey, request, userId)` 等多表写路径同样加事务，保证从代理入口进入。

## 3. P2 与结构债

### 3.1 P2-1 `task_key` 并发

- 新表 **`project_task_seq`**：`project_id` + `next_number`；**不使用数据库 FOREIGN KEY**。
- 创建任务时在事务内对对应行 **行锁**（如 `SELECT ... FOR UPDATE`），取号、递增、写任务；首用项目时插入 seq 行。

### 3.2 P2-2 N+1

- 评论 mention：批量查询项目成员 `Set<Long>`，禁止循环单条成员校验。
- 任务导入：批量插入 + 批量回读或主键回填，避免每行 `selectById`。

### 3.3 P2-3 附件下载

- 存储客户端提供流式读取，避免 `readAllBytes()`。
- Controller/Service 返回流式响应；可配置最大下载体积与超时，与全局异常风格一致。

### 3.4 `TaskPermissionGuard` 与 `TaskService` 拆分

- **Guard**：`requireProjectMember`、`requireTaskAccessByKey(taskKey, userId)` 等，供活动、评论、命令路径复用。
- **TaskQueryService**：列表、详情、收藏列表、子任务计数/收藏状态填充等只读。
- **TaskCommandService**：create、update、收藏增删、活动记录、状态/进度联动。
- **TaskImportService**：`importTasks` 及导入校验。
- **TaskService**：可实现为薄门面委托三子服务，以减少 Controller 一次性改动面（实现计划二选一定案）。

## 4. 测试与回滚

- 全量 `rtk mvn -q test` 必须通过；能补集成测试则补，否则维护手工清单（非成员 activities、恶意邮箱、并发建任务等）。
- 密码：新用户仅 bcrypt；老明文登录一次后变为 bcrypt。
- 回滚：代码整块回滚；若生产已执行 `project_task_seq` DDL，需说明是否保留表或提供 down 步骤。

## 5. 非目标（本设计不展开）

- 全站威胁建模、限流、审计日志等未在评审条目中列为 P0–P2 的项，不纳入本变更范围。
