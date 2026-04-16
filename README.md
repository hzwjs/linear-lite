# Linear Lite

轻量级任务/看板全栈应用：Vue 3 + TypeScript + Vite 前端，Spring Boot 3 + MyBatis-Plus + MySQL 8 后端。Phase 2 支持多用户登录、项目隔离与任务负责人（Assignee）。Phase 3 增加项目新建/设置、Board/List 视图切换、任务截止日与完成时间。

## 快速开始

### 环境要求

- **后端**：JDK 17+、Maven、MySQL 8.x
- **前端**：Node.js 18+、npm

### 1. 数据库初始化

先创建库并执行合并后的初始化脚本（建表 + 种子数据，幂等 INSERT）：

```bash
# 建库（若尚未创建）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;"

# 建表与种子（预置用户 admin/user1/user2/alice/bob，项目 Engineering、Design）
mysql -u root -p linear_lite < linear-lite-server/src/main/resources/schema.sql
```

脚本路径：`linear-lite-server/src/main/resources/schema.sql`

更多用法见 [linear-lite-server/README.md](linear-lite-server/README.md)。

### 2. 启动后端

在 `linear-lite-server` 目录下：

```bash
cd linear-lite-server
cp .env.properties.example .env.properties
# 按本机实际情况修改 .env.properties（至少检查 MYSQL_PASSWORD）
mvn spring-boot:run
```

默认端口 **9080**。常用环境变量（不设则用默认值）：


| 变量                     | 默认值                            | 说明                      |
| ---------------------- | ------------------------------ | ----------------------- |
| `MYSQL_HOST`           | localhost                      | MySQL 主机                |
| `MYSQL_PORT`           | 3306                           | 端口                      |
| `MYSQL_DATABASE`       | linear_lite                    | 数据库名                    |
| `MYSQL_USERNAME`       | root                           | 数据库用户                   |
| `MYSQL_PASSWORD`       | （空）                            | 数据库密码                   |
| `SERVER_PORT`          | 9080                           | 服务端口                    |
| `JWT_SECRET`           | linear-lite-default-secret-... | 生产环境请修改                 |
| `R2_ENABLED`           | false                          | 是否启用 Cloudflare R2 图片存储 |
| `R2_ENDPOINT`          | （空）                            | R2 S3 兼容端点              |
| `R2_ACCESS_KEY_ID`     | （空）                            | R2 Access Key ID        |
| `R2_SECRET_ACCESS_KEY` | （空）                            | R2 Secret Access Key    |
| `R2_BUCKET`            | （空）                            | R2 bucket 名称            |
| `R2_PUBLIC_BASE_URL`   | （空）                            | 图片公开访问基础 URL            |
| `R2_REGION`            | auto                           | R2 区域，通常保持 `auto`       |


示例：指定密码与端口

```bash
export MYSQL_PASSWORD=your_password
export SERVER_PORT=9080
mvn spring-boot:run
```

如果要启用任务描述中的图片上传，在 `linear-lite-server/.env.properties` 中补充本地 R2 配置后直接启动后端即可。Spring Boot 会自动加载这个文件：

```bash
cd linear-lite-server
mvn spring-boot:run
```

### 3. 启动前端

在仓库根目录：

```bash
pnpm install
pnpm dev
```

前端默认运行在 **[http://localhost:5173](http://localhost:5173)**。

- **开发环境**：Vite 将 `/api` 代理到 `http://localhost:9080`，无需配置即可访问后端。
- **生产/自定义后端地址**：构建时设置 `VITE_API_BASE_URL`，例如：
`VITE_API_BASE_URL=https://api.example.com npm run build`

### 4. 登录与验证

使用种子用户登录，例如：`user1` / `user123`、`user2` / `user123`。验收步骤见 [docs/phase2-implementation-plan.md](docs/phase2-implementation-plan.md) 中「Manual Verification」及「人工执行步骤清单与预期结果」。Phase 3 验收见 [docs/phase3-implementation-plan.md](docs/phase3-implementation-plan.md)。

## 单 JAR 部署

前端会打包进同一 JAR，部署后通过同一端口访问页面与 API。

1. **环境**：本机需已安装 JDK 17+、Maven、Node.js 与 npm、MySQL 8.x。
2. **打包**：在 `linear-lite-server` 目录执行：
  ```bash
   mvn clean package
  ```
3. **运行**：`java -jar target/linear-lite-server-0.1.0-SNAPSHOT.jar`（数据库等环境变量同「启动后端」）。
4. **访问**：浏览器打开 `http://localhost:9080/` 即可使用前端；API 仍为 `/api`，同源部署无需配置 `VITE_API_BASE_URL`。

单 JAR 部署时无需设置 `VITE_API_BASE_URL`。

### 部署后出现 401

1. **打包时不要设 `VITE_API_BASE_URL`**
  单 JAR 同源部署时，前端应使用相对路径 `/api`。若构建时设置了 `VITE_API_BASE_URL`，请求会发到错误地址或跨域，易出现 401。  
   正确：`npm run build` 或 `mvn package` 时不设置该变量。
2. **登录接口返回 401**
  表示用户名/密码错误。确认服务器数据库已执行 `schema.sql`，使用种子账号如 `user1` / `user123` 登录。
3. **登录成功后其他接口 401**
  - 浏览器开发者工具 → Network：看该请求是否带 `Authorization: Bearer xxx`。若无，多为前端请求未走带 token 的 axios 实例或 baseURL 指向了别的域名。  
  - 服务端：多实例或重启后需保证 `JWT_SECRET` 一致；未配置时使用默认值，一般同机单进程无问题。
4. **前后端不同域（如前端 [https://app.example.com，API](https://app.example.com，API) [https://api.example.com）**](https://api.example.com）)
  在服务器环境变量中设置后端允许的前端来源，例如：  
   `CORS_ALLOWED_ORIGINS=https://app.example.com`（多源逗号分隔）。  
   单 JAR 同机同端口访问时，与当前服务同 host+port 的 Origin 会自动放行，无需配置。

## Phase 3 能力摘要

- **项目**：侧栏「新建」创建项目；项目右侧齿轮打开设置，可修改名称与标识符。
- **视图**：主界面右上角 Board / List 切换；列表为高密度表格、按状态分组折叠；视图偏好存 localStorage。
- **任务时间**：创建/编辑任务可设截止日（Due Date）；任务进入 Done 时自动记录完成时间，编辑器中只读展示；超期未完成在卡片与列表中飘红提示。
- **任务导入**：任务页顶部支持导入 `.csv` / `.xlsx`；第一版支持 `title`、`description`、`status`、`priority`、`assignee`、`dueDate`、`importId`、`parentImportId`，仅新增到当前项目，单次最多 `800` 行，整批成功或失败。

## 仓库结构

- **根目录**：Vue 3 + Vite 前端（`src/`、`vite.config.ts` 等）
- **linear-lite-server/**：Spring Boot 后端（API、实体、schema/种子 SQL）
- **docs/**：Phase 2/3 实现计划、任务清单与验收说明

