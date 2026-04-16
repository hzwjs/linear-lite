# linear-lite-server

Phase 2 后端：Spring Boot 3.x + MyBatis-Plus + MySQL 8。

## 目录结构简述

```
linear-lite-server/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/linearlite/server/
    │   ├── LinearLiteServerApplication.java   # 启动类
    │   ├── common/
    │   │   └── ApiResponse.java                # 统一响应 code/message/data
    │   ├── config/
    │   │   └── WebConfig.java                  # CORS（允许 Vite 开发源）
    │   ├── entity/
    │   │   ├── User.java
    │   │   ├── Project.java
    │   │   └── Task.java
    │   ├── exception/
    │   │   └── GlobalExceptionHandler.java    # 全局异常处理
    │   └── mapper/
    │       ├── UserMapper.java
    │       ├── ProjectMapper.java
    │       └── TaskMapper.java
    └── resources/
        ├── application.yml
        └── schema.sql                          # 建表 + 可选种子数据（合并脚本）
```

## 启动方式

**环境要求**：JDK 17+、Maven、MySQL 8.x。

1. 在 MySQL 中执行 `schema.sql`（见下方，含种子数据）。
2. 复制本地配置模板并按需修改：

```bash
cp .env.properties.example .env.properties
```

3. 在项目根目录 `linear-lite-server/` 下执行：

```bash
mvn spring-boot:run
```

`application.yml` 已去除敏感默认凭据。数据库、邮件与对象存储配置建议全部通过环境变量注入。

**环境变量**（建议显式配置）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST` | localhost | MySQL 主机 |
| `MYSQL_PORT` | 3306 | MySQL 端口 |
| `MYSQL_DATABASE` | linear_lite | 数据库名 |
| `MYSQL_USERNAME` | root | 数据库用户 |
| `MYSQL_PASSWORD` | （空） | 数据库密码 |
| `SERVER_PORT` | 9080 | 服务端口 |
| `MAIL_HOST` | （空） | SMTP 主机 |
| `MAIL_PORT` | 465 | SMTP 端口 |
| `MAIL_USERNAME` | （空） | SMTP 用户 |
| `MAIL_PASSWORD` | （空） | SMTP 密码 |
| `R2_ENABLED` | false | 是否启用 Cloudflare R2 图片存储 |
| `R2_ENDPOINT` | （空） | R2 S3 兼容端点 |
| `R2_ACCESS_KEY_ID` | （空） | R2 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | （空） | R2 Secret Access Key |
| `R2_BUCKET` | （空） | R2 bucket 名称 |
| `R2_PUBLIC_BASE_URL` | （空） | 图片公开访问基础 URL |
| `R2_REGION` | auto | R2 区域，通常保持 `auto` |
| `STORAGE_MAX_DOWNLOAD_BYTES` | 10485760 | 附件下载最大字节数（默认 10MB） |

示例（推荐）：使用本地配置文件启动（无需每次 export）

```bash
cp .env.properties.example .env.properties
# 按本机实际情况修改 .env.properties（例如 MYSQL_PASSWORD）
mvn spring-boot:run
```

如需启用任务描述图片上传，可在 `.env.properties` 中填写 R2 配置，Spring Boot 会在启动时自动加载：

```bash
cd linear-lite-server
mvn spring-boot:run
```

当前项目已使用环境变量方式读取 R2 配置；`.env.properties` 只是本机开发时的自动加载入口，不应提交到仓库。

## schema.sql 的用法

- **路径**：`src/main/resources/schema.sql` — 建表（含 `task_key`、`due_date`、`completed_at`、`task_attachments` 等）与种子数据（同一文件）。
- **已有旧库**：若表结构落后于当前 `schema.sql`，需自行 `ALTER` / 补表，或从 git 历史取已删除的增量脚本对照执行。
- **`project_task_seq` 迁移门禁**：对历史库上线前先执行 `schema.sql` 中“归档：project_task_seq 迁移与校验 SQL”段落：
  - 先执行 backfill `INSERT ... ON DUPLICATE KEY UPDATE`
  - 再执行 3 段校验 `SELECT`（均应返回空结果集）

- **示例（本地或测试库）**：

```bash
cd linear-lite-server/src/main/resources

# 1. 建库（若尚未创建）
mysql -h localhost -P 3306 -u app_user -p -e "CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;"

# 2. 建表 + 种子数据
mysql -h localhost -P 3306 -u app_user -p linear_lite < schema.sql
```

- **其它主机**：将主机、端口、用户名替换为目标环境值即可。种子 `INSERT` 使用 `ON DUPLICATE KEY UPDATE`，重复执行相对幂等。
