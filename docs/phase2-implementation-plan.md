# Linear Lite - Phase 2 Implementation Plan (Team-Ready Foundation)

基于 `phase2-ideas.md` 确定的演进目标，我们将把现有基于本地存储的前端单体应用，升级为具备**联机共享 (Backend)**、**责任到人 (Users/Assignees)** 以及**任务隔离 (Projects)** 的团队可用型全栈系统。

## User Review Required

> [!IMPORTANT]
>
> 1. **后端技术栈确认**：计划在当前工程或同级新目录建立后端工程。推荐使用 **Spring Boot 3.x + MyBatis-Plus + MySQL 8.x** 实现快速的实体映射和 RESTful CRUD 操作。
> 2. **最简登录态方案**：为了快速打通团队最小闭环，不引入沉重的全套 OAuth2 流程体系。计划提供一个简单的 `/api/login` 接口返回 JWT Token（前端保存在 `localStorage` 中），前端带着 Token 请求接口。系统预置几个 Mock 成员账号用于验证。
> 3. **工程目录结构**：由于这是全栈项目，打算把现有的前端代码移交至 `linear-lite-web` 目录（或子模块），后端放置在 `linear-lite-server` 目录。请确认代码库拆分方式。

## Proposed Changes

### 1. Repository Structure & Backend Bootstrap (后端工程基石)

- 整理目录，初始化 Spring Boot 工程。
- 集成跨域配置 (CORS) 以便 Vue (Vite) 本地代理调用。
- 配置全局异常处理 (GlobalExceptionHandler) 和标准响应结构 (ApiResponse)。

#### [NEW] API 响应规范

- `code`: 状态码 (200 成功，非 200 失败)
- `message`: 错误或提示信息
- `data`: 业务载荷

### 2. Database Schema & Domain Mapping (数据模型建表)

需要建立三张核心表（符合 P0 阶段的核心三要素）：

#### [NEW] 表结构定义

1. `users` 表：`id`, `username`, `password`, `avatar_url`, `created_at`
2. `projects` 表：`id`, `name`, `identifier` (如 "ENG", "PROD" 作为 Issue ID 前缀), `created_at`
3. `tasks` (改造现有领域模型)：
   - `id`: (字符串或自增，如 "ENG-12") — *实现说明：当前 schema 为自增 `id` (BIGINT) + 对外展示 `task_key` (VARCHAR，如 ENG-1)；API 路径与前端使用 `task_key` 作为任务标识。*
   - `title`, `description`, `status`, `priority`
   - `project_id`: 外键关联 `projects`
   - `creator_id`: 外键关联 `users` (创建者)
   - `assignee_id`: 外键关联 `users` (负责人)
   - `created_at`, `updated_at`

### 3. Server API Layers (核心接口开发)

#### [NEW] `AuthController` & `UserService`

- `POST /api/auth/login` (返回 Token)
- `GET /api/users` (获取团队成员列表，供 Assignee 选择)

#### [NEW] `ProjectController`

- `GET /api/projects` (侧边栏项目列表)
- `POST /api/projects` (新建项目空间)

#### [MODIFY] `TaskController` (接管原本的 Front-end Mock)

- `GET /api/tasks?projectId={id}` (按项目获取任务)
- `POST /api/tasks` (创建任务，后端自动绑定 creator_id 并生成如 "PROD-1" 的自增短 ID)
- `PUT /api/tasks/{id}` (更新状态、优先级、负责人)

### 4. Frontend Integration & Refactoring (前端适配与打通)

前端将拔除 Phase 1 的 Mock 桩，对接真实的 Axios/Fetch 服务。

#### [MODIFY] `src/services/taskService.ts` -> `api/index.ts`

- 引入 Axios，配置全局 Token 拦截器和 401 登出跳回处理。
- 完全对接后端的 Task, Project, User 接口。

#### [MODIFY] `src/store/` (Pinia)

- 新增 `authStore.ts`: 维护 `currentUser` 和 `jwtToken`。
- 新增 `projectStore.ts`: 维护侧边栏所需的项目列表与当前选中的 `activeProjectId`。
- 改造 `taskStore.ts`: 原有逻辑不变，但数据源切换为真实 API 请求，并在获取时附加 `activeProjectId` 过滤。

#### [NEW & MODIFY] UI Layer

- **[NEW] `LoginView.vue`**: 简单的登录页。
- **[MODIFY] `App.vue` / Layout**: 建立全局的两列/三列布局。左侧新增 Sidebar，显示 Projects 列表；主区域显示所选 Project 的 BoardView。
- **[MODIFY] `TaskCard.vue` & `TaskEditor.vue`**:
  - 增加 **Assignee** 选择框和头像展示。
  - 创建任务时屏蔽 ID (交由后端生成带前缀的真实 ID)。

---

## 验证计划 (Verification Plan)

### Automations / Scripts

- 提供一份 `schema.sql` 和 `data-init.sql`，帮助一键在本地 MySQL 刷入表结构和 3-5 个预置用户（如 admin, user1, user2）及两个示例 Project。

### Manual Verification

1. **多端/多人登录**：启动后端和多开浏览器（如正常窗口与无痕窗口）。使用 user1 和 user2 分别登录。
2. **项目隔离与呈现**：在左侧边栏切换 "Engineering" 和 "Design" 项目，确认右侧的 Kanban 列表成功切分并刷新，数据互不串流。
3. **分配闭环**：user1 在任务看板中，打开一条刚创建的 Issue，进入编辑模式，将 `Assignee` 从自己更改为 user2 并保存。
4. **共享更新可见性**：此时 user2 在其浏览器的页面刷新（或重新进板），应当能看到该任务头像已变为自己，且能正确进行状态推进。

#### 人工执行步骤清单与预期结果（便于无自动化时按项勾选）

| 项 | 步骤 | 预期结果 |
|----|------|----------|
| **1. 多端/多人登录** | ① 启动后端（`linear-lite-server` 下 `mvn spring-boot:run`）与前端（仓库根目录 `npm run dev`）。② 浏览器 A 打开 http://localhost:5173，用 user1 / user123 登录。③ 无痕或另一浏览器 B 打开同 URL，用 user2 / user123 登录。 | 两个窗口均能进入看板；各自侧栏显示同一项目列表；两边的“当前用户”或身份标识不同（user1 vs user2），会话互不踢出。 |
| **2. 项目隔离与呈现** | ① 在浏览器 A（user1）左侧边栏点击「Engineering」。② 记录当前看板上的任务数量或若干任务 ID。③ 再点击「Design」。④ 观察看板列表是否变化。⑤ 在「Design」下新建一条任务，再切回「Engineering」。 | 切换项目后看板仅显示当前项目任务；Engineering 与 Design 任务列表互不混在一起；新建在 Design 下的任务不会出现在 Engineering 看板。 |
| **3. 分配闭环** | ① user1 在任一项目中创建或打开一条任务，记下任务 ID（如 ENG-1）。② 打开编辑，将 Assignee 从当前用户改为 user2，保存。③ 在 user2 的浏览器 B 中刷新页面（或重新进入该同一项目）。 | user2 的看板中该任务显示 assignee 为 user2（头像/名称）；user2 能编辑该任务并改状态等。 |
| **4. 共享更新可见性** | ① 延续上一步：user2 将同一任务状态从 backlog 改为 in progress 或 done 并保存。② 在 user1 的浏览器 A 中刷新或重新进入该项目。 | user1 看到该任务状态已更新为 user2 修改后的值；Assignee 仍为 user2；两人所见数据一致，体现共享更新可见。 |
