# Linear Lite

轻量级任务/看板全栈应用：Vue 3 + TypeScript + Vite 前端，Spring Boot 3 + MyBatis-Plus + MySQL 8 后端。Phase 2 支持多用户登录、项目隔离与任务负责人（Assignee）。Phase 3 增加项目新建/设置、Board/List 视图切换、任务截止日与完成时间。

## 快速开始

### 环境要求

- **后端**：JDK 17+、Maven、MySQL 8.x
- **前端**：Node.js 18+、npm

### 1. 数据库初始化

先创建库并执行建表与种子数据（顺序不可颠倒）：

```bash
# 建库（若尚未创建）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;"

# 建表
mysql -u root -p linear_lite < linear-lite-server/src/main/resources/schema.sql

# 种子数据（预置用户 admin/user1/user2/alice/bob，项目 Engineering、Design）
mysql -u root -p linear_lite < linear-lite-server/src/main/resources/data-init.sql

# （可选）Phase 3：任务时间字段 due_date、completed_at
mysql -u root -p linear_lite < linear-lite-server/src/main/resources/schema-v3-task-timeline.sql
```

脚本路径（供 IDE 或其它工具使用）：

- 建表：`linear-lite-server/src/main/resources/schema.sql`
- 种子：`linear-lite-server/src/main/resources/data-init.sql`
- Phase 3 增量：`linear-lite-server/src/main/resources/schema-v3-task-timeline.sql`

更多用法见 [linear-lite-server/README.md](linear-lite-server/README.md)。

### 2. 启动后端

在 `linear-lite-server` 目录下：

```bash
cd linear-lite-server
mvn spring-boot:run
```

默认端口 **8080**。常用环境变量（不设则用默认值）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST` | localhost | MySQL 主机 |
| `MYSQL_PORT` | 3306 | 端口 |
| `MYSQL_DATABASE` | linear_lite | 数据库名 |
| `MYSQL_USERNAME` | root | 数据库用户 |
| `MYSQL_PASSWORD` | （空） | 数据库密码 |
| `SERVER_PORT` | 8080 | 服务端口 |
| `JWT_SECRET` | linear-lite-default-secret-... | 生产环境请修改 |

示例：指定密码与端口

```bash
export MYSQL_PASSWORD=your_password
export SERVER_PORT=8080
mvn spring-boot:run
```

### 3. 启动前端

在仓库根目录：

```bash
npm install
npm run dev
```

前端默认运行在 **http://localhost:5173**。

- **开发环境**：Vite 将 `/api` 代理到 `http://localhost:8080`，无需配置即可访问后端。
- **生产/自定义后端地址**：构建时设置 `VITE_API_BASE_URL`，例如：
  `VITE_API_BASE_URL=https://api.example.com npm run build`

### 4. 登录与验证

使用种子用户登录，例如：`user1` / `user123`、`user2` / `user123`。验收步骤见 [docs/phase2-implementation-plan.md](docs/phase2-implementation-plan.md) 中「Manual Verification」及「人工执行步骤清单与预期结果」。Phase 3 验收见 [docs/phase3-implementation-plan.md](docs/phase3-implementation-plan.md)。

## Phase 3 能力摘要

- **项目**：侧栏「新建」创建项目；项目右侧齿轮打开设置，可修改名称与标识符。
- **视图**：主界面右上角 Board / List 切换；列表为高密度表格、按状态分组折叠；视图偏好存 localStorage。
- **任务时间**：创建/编辑任务可设截止日（Due Date）；任务进入 Done 时自动记录完成时间，编辑器中只读展示；超期未完成在卡片与列表中飘红提示。

## 仓库结构

- **根目录**：Vue 3 + Vite 前端（`src/`、`vite.config.ts` 等）
- **linear-lite-server/**：Spring Boot 后端（API、实体、schema/种子 SQL）
- **docs/**：Phase 2/3 实现计划、任务清单与验收说明
