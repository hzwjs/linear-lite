# Phase 2 任务清单 (Team-Ready Foundation)

基于 `prd/phase2-ideas.md` 与 `phase2-implementation-plan.md` 拆解。执行顺序建议：Backend → Users & Assignees → Projects → Frontend 打通。

**图例**：`[ ]` 未开始 | `[x]` 已完成 | 依赖用 `←` 标注。

---

## 0. 前置决策（需确认后再开发）

| ID | 任务 | 状态 | 备注 |
|----|------|------|------|
| P2-0.1 | 确认后端技术栈：Spring Boot 3.x + MyBatis-Plus + MySQL 8.x | [x] | 见 implementation plan User Review |
| P2-0.2 | 确认登录方案：简单 `/api/login` + JWT，Token 存 localStorage | [x] | 不引入完整 OAuth2 |
| P2-0.3 | 确认工程目录：前端 `linear-lite-web` / 后端 `linear-lite-server` 或其它 | [x] | 后端 linear-lite-server，前端保留根目录 |

---

## 1. 仓库结构与后端工程基石

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-1.1 | 创建后端工程目录并初始化 Spring Boot 3.x 项目 | [x] | P2-0.1, P2-0.3 |
| P2-1.2 | 配置 CORS，允许 Vue (Vite) 本地开发域名访问 | [x] | P2-1.1 |
| P2-1.3 | 实现全局异常处理 (GlobalExceptionHandler) | [x] | P2-1.1 |
| P2-1.4 | 定义统一 API 响应结构：code / message / data | [x] | P2-1.1 |

---

## 2. 数据库与领域映射

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-2.1 | 编写 `schema.sql`：`users` 表 (id, username, password, avatar_url, created_at) | [x] | P2-1.1 |
| P2-2.2 | 编写 `schema.sql`：`projects` 表 (id, name, identifier, created_at) | [x] | P2-2.1 |
| P2-2.3 | 编写 `schema.sql`：`tasks` 表（含 project_id, creator_id, assignee_id 外键） | [x] | P2-2.2 |
| P2-2.4 | 编写 `data-init.sql`：预置 3–5 个用户、2 个示例项目 | [x] | P2-2.3 |
| P2-2.5 | 配置 MyBatis-Plus 与实体映射 (User, Project, Task) | [x] | P2-2.3 |

---

## 3. 服务端 API 层

### 3.1 鉴权与用户

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-3.1 | 实现 `POST /api/auth/login`，校验用户名密码并返回 JWT | [x] | P2-2.5, P2-0.2 |
| P2-3.2 | 实现 `GET /api/users`，返回团队成员列表（供 Assignee 选择） | [x] | P2-2.5 |
| P2-3.3 | 配置 JWT 校验过滤器/拦截器，保护需登录的接口 | [x] | P2-3.1 |

### 3.2 项目

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-3.4 | 实现 `GET /api/projects`（侧边栏项目列表） | [x] | P2-2.5, P2-3.3 |
| P2-3.5 | 实现 `POST /api/projects`（新建项目） | [x] | P2-3.4 |

### 3.3 任务（接管原前端 Mock）

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-3.6 | 实现 `GET /api/tasks?projectId={id}`，按项目返回任务 | [x] | P2-2.5, P2-3.3 |
| P2-3.7 | 实现 `POST /api/tasks`，创建任务并绑定 creator_id、生成带前缀 ID（如 PROD-1） | [x] | P2-3.6 |
| P2-3.8 | 实现 `PUT /api/tasks/{id}`，更新状态、优先级、负责人等 | [x] | P2-3.6 |

---

## 4. 前端适配与打通

### 4.1 请求层与状态

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-4.1 | 引入 Axios，封装 `api/index.ts`（或重构 `taskService.ts`），配置 baseURL | [x] | P2-3.1 |
| P2-4.2 | 请求拦截器：在 Header 中附加 JWT Token | [x] | P2-4.1 |
| P2-4.3 | 响应拦截器：401 时清除 Token 并跳转登录页 | [x] | P2-4.1 |
| P2-4.4 | 对接后端 Task / Project / User 接口（GET/POST/PUT） | [x] | P2-3.6, P2-3.4, P2-3.2 |
| P2-4.5 | 新增 `authStore.ts`：currentUser、jwtToken、login/logout | [x] | P2-4.2 |
| P2-4.6 | 新增 `projectStore.ts`：项目列表、activeProjectId | [x] | P2-4.4 |
| P2-4.7 | 改造 `taskStore.ts`：数据源改为 API，按 activeProjectId 过滤 | [x] | P2-4.4, P2-4.6 |
| P2-4.8 | 移除前端对 localStorage 任务数据的依赖 | [x] | P2-4.7 |

### 4.2 UI 与布局

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-4.9 | 新增 `LoginView.vue`（用户名/密码表单，调用 login 后写 Token 并跳转） | [x] | P2-4.5 |
| P2-4.10 | 调整 `App.vue`/Layout：全局两列/三列布局，左侧 Sidebar | [x] | P2-4.6 |
| P2-4.11 | Sidebar 展示项目列表，点击切换 activeProjectId 并刷新看板 | [x] | P2-4.10, P2-4.6 |
| P2-4.12 | 主区域根据 activeProjectId 展示对应项目的 BoardView | [x] | P2-4.11, P2-4.7 |
| P2-4.13 | 未登录时路由守卫跳转登录页 | [x] | P2-4.5, P2-4.9 |

### 4.3 任务卡片与编辑

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-4.14 | `TaskCard.vue`：展示 Assignee 头像/名称 | [x] | P2-4.4 |
| P2-4.15 | `TaskEditor.vue`：增加 Assignee 选择框（下拉拉取 users 列表） | [x] | P2-4.4 |
| P2-4.16 | 创建任务时不再在前端生成 ID，由后端返回带前缀 ID | [x] | P2-3.7, P2-4.4 |

---

## 5. 验证与收尾

| ID | 任务 | 状态 | 依赖 |
|----|------|------|------|
| P2-5.1 | 本地执行 schema.sql + data-init.sql，确认表与种子数据正确 | [x] | P2-2.4 |
| P2-5.2 | 多端登录：两浏览器用不同用户登录，确认会话隔离 | [x] | P2-4.9, P2-3.1 |
| P2-5.3 | 项目隔离：切换侧栏项目，确认看板数据按项目切分、互不串流 | [x] | P2-4.11, P2-4.12 |
| P2-5.4 | 分配闭环：user1 将某任务 Assignee 改为 user2，user2 刷新后可见 | [x] | P2-4.15, P2-3.8 |
| P2-5.5 | 更新 README：后端启动方式、环境变量、MySQL 要求 | [x] | P2-5.1 |

---

## 完成标准（Phase 2 验收）

- [x] 多成员可同时访问同一系统（不同浏览器/用户登录）
- [x] 前端完全脱离 localStorage 任务数据，全部走真实接口
- [x] 每个任务有明确归属：项目、创建者、负责人
- [x] UI 上不同项目的任务在不同板块下互不干扰地流转

---

## 任务依赖简图

```
P2-0.x (决策)
  → P2-1.x (后端基石)
       → P2-2.x (DB + 实体)
            → P2-3.x (API：Auth → Projects → Tasks)
                 → P2-4.x (前端：api/store → Layout → Sidebar → Board/Card/Editor)
                      → P2-5.x (验证)
```
