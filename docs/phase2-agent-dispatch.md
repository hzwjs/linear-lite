# Phase 2 多智能体并行派发计划

基于 `phase2-task-list.md` 与 dispatching-parallel-agents 原则：按依赖拆成多波，同一波内互不依赖的任务由多 Agent 并行执行。

**执行状态**：Wave 1～Wave 4 已全部派发并完成；任务清单见 `phase2-task-list.md`（已标完成）。

---

## 前置假设

- P2-0.x 已按 implementation plan 默认采纳：Spring Boot 3.x + MyBatis-Plus + MySQL 8.x；简单 JWT 登录；后端目录 `linear-lite-server`（与现有前端同仓库）。

---

## 波次总览

| 波次 | 内容 | Agent 数 | 依赖 |
|------|------|----------|------|
| Wave 1 | 后端工程基石 + 数据库与实体 (P2-1.x, P2-2.x) | 1 | 无 |
| Wave 2 | 服务端 API：鉴权/用户、项目、任务 (P2-3.x) | 3 并行 | Wave 1 |
| Wave 3a | 前端请求层与状态 (P2-4.1–4.8) | 1 | Wave 2 |
| Wave 3b | 前端 UI：登录/布局 与 卡片/编辑 (P2-4.9–4.16) | 2 并行 | Wave 3a |
| Wave 4 | 验证与收尾 (P2-5.x) | 1 | Wave 3b |

---

## Wave 1：后端基石 + 数据库（单 Agent）

**任务 ID**：P2-1.1～P2-1.4，P2-2.1～P2-2.5

**目标**：在仓库内创建可运行的后端骨架与数据库脚本，供 Wave 2 实现 API。

**约束**：
- 仅在后端目录 `linear-lite-server` 内创建/修改文件，不改动现有 `src/` 前端代码。
- 参考 `docs/phase2-implementation-plan.md` 的 API 响应规范与表结构。

**交付**：
- `linear-lite-server/` 为可启动的 Spring Boot 3.x 项目（含 CORS、GlobalExceptionHandler、统一 ApiResponse）。
- `linear-lite-server/schema.sql` 含 users、projects、tasks 三表。
- `linear-lite-server/data-init.sql` 含 3～5 个用户、2 个示例项目。
- MyBatis-Plus 配置及 User、Project、Task 实体与 Mapper 占位（可无业务方法）。

**完成时返回**：简要说明目录结构、启动命令、以及 schema/init 的路径与用法。

---

## Wave 2：服务端 API（3 Agent 并行）

**前置条件**：Wave 1 已完成，`linear-lite-server` 可启动，三表与实体已存在。

### Agent 2A：鉴权与用户 API

**任务 ID**：P2-3.1～P2-3.3

**范围**：仅实现鉴权与用户相关接口与安全配置。

**交付**：
- `POST /api/auth/login`：校验用户名密码，返回 JWT（及统一 ApiResponse 包装）。
- `GET /api/users`：返回团队成员列表（供前端 Assignee 选择）。
- JWT 校验过滤器/拦截器：保护除 `/api/auth/login` 外的接口；未授权返回 401。

**约束**：不修改 Project、Task 的 Controller/Service；可新增依赖（如 jjwt）。

**完成时返回**：列出新增/修改的文件及接口 URL、请求/响应示例。

---

### Agent 2B：项目 API

**任务 ID**：P2-3.4～P2-3.5

**范围**：仅实现项目相关 CRUD。

**交付**：
- `GET /api/projects`：返回项目列表（侧边栏用）。
- `POST /api/projects`：新建项目（name、identifier 等）。

**约束**：不修改 Auth、Task 的 Controller/Service；接口需受 JWT 保护。

**完成时返回**：列出新增/修改的文件及接口 URL、请求/响应示例。

---

### Agent 2C：任务 API

**任务 ID**：P2-3.6～P2-3.8

**范围**：仅实现任务相关接口，接管原前端 Mock。

**交付**：
- `GET /api/tasks?projectId={id}`：按项目返回任务列表。
- `POST /api/tasks`：创建任务，绑定当前用户为 creator_id，生成带项目前缀的 ID（如 PROD-1）。
- `PUT /api/tasks/{id}`：更新任务（状态、优先级、负责人等）。

**约束**：不修改 Auth、Project 的 Controller/Service；接口需受 JWT 保护；当前用户从 JWT 解析。

**完成时返回**：列出新增/修改的文件及接口 URL、请求/响应示例。

---

## Wave 3a：前端请求层与状态（单 Agent）

**任务 ID**：P2-4.1～P2-4.8

**前置条件**：Wave 2 已完成，后端三个模块 API 可用。

**目标**：前端完全对接后端，移除对 localStorage 任务数据的依赖。

**交付**：
- Axios 封装（如 `src/services/api/index.ts` 或重构 `taskService.ts`），baseURL 可配置，请求头带 JWT，401 时清 Token 并跳转登录。
- 对接 Task、Project、User 的 GET/POST/PUT。
- 新增 `authStore.ts`（currentUser、jwtToken、login/logout）、`projectStore.ts`（项目列表、activeProjectId）、改造 `taskStore.ts`（数据源改为 API，按 activeProjectId 过滤）。
- 移除前端对 localStorage 任务数据的读写。

**约束**：不实现登录页、侧栏、看板布局或卡片/编辑器的 UI 改动；只做请求层与 store。

**完成时返回**：列出修改/新增文件及主要 API 调用与 store 方法对应关系。

---

## Wave 3b：前端 UI（2 Agent 并行）

**前置条件**：Wave 3a 已完成，authStore、projectStore、taskStore 与 API 已就绪。

### Agent 3b-1：登录与布局

**任务 ID**：P2-4.9～P2-4.13

**范围**：登录页、全局布局、侧栏、路由守卫。

**交付**：
- `LoginView.vue`：用户名/密码表单，调用 login 后写 Token 并跳转。
- `App.vue`/Layout：两列/三列布局，左侧 Sidebar 展示项目列表，点击切换 activeProjectId 并刷新看板；主区域按 activeProjectId 展示 BoardView。
- 路由守卫：未登录跳转登录页。

**约束**：不修改 TaskCard、TaskEditor 的 Assignee 展示与选择逻辑。

**完成时返回**：列出修改/新增的组件与路由配置。

---

### Agent 3b-2：任务卡片与编辑器

**任务 ID**：P2-4.14～P2-4.16

**范围**：卡片与编辑器的 Assignee 展示与创建时 ID 行为。

**交付**：
- `TaskCard.vue`：展示 Assignee 头像/名称。
- `TaskEditor.vue`：Assignee 选择框（下拉拉取 users 列表）；创建任务时不再在前端生成 ID，使用后端返回的带前缀 ID。

**约束**：不修改布局、Sidebar、LoginView、路由守卫。

**完成时返回**：列出修改的组件与涉及的 API/store 调用。

---

## Wave 4：验证与收尾（单 Agent）

**任务 ID**：P2-5.1～P2-5.5

**前置条件**：Wave 3b 已完成，前后端联调通过。

**交付**：
- 确认 schema.sql + data-init.sql 可本地执行并符合文档。
- 执行 phase2-implementation-plan 中的 Manual Verification 四项（多端登录、项目隔离、分配闭环、共享更新可见性），并记录结果。
- 更新 README：后端启动方式、环境变量、MySQL 要求。

**完成时返回**：验证结果摘要与 README 变更说明。

---

## 执行顺序

1. **本次**：派发 Wave 1（1 个 Agent）。
2. **Wave 1 完成后**：并行派发 Wave 2 的 3 个 Agent（2A、2B、2C）。
3. **Wave 2 全部完成后**：派发 Wave 3a（1 个 Agent）。
4. **Wave 3a 完成后**：并行派发 Wave 3b 的 2 个 Agent（3b-1、3b-2）。
5. **Wave 3b 全部完成后**：派发 Wave 4（1 个 Agent）。

每次派发前请确认对应波次的前置条件已满足（尤其是后端可启动、接口可调）。
