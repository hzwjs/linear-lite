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
2. 在项目根目录 `linear-lite-server/` 下执行：

```bash
mvn spring-boot:run
```

**当前默认数据库**（已在 `application.yml` 中配置）：`8.148.189.106:3306`，库名 `linear_lite`，用户 `root`。如需改用本地库，可设置环境变量覆盖。

**可选环境变量**（不设则用上述默认值）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST` | 8.148.189.106 | MySQL 主机 |
| `MYSQL_PORT` | 3306 | MySQL 端口 |
| `MYSQL_DATABASE` | linear_lite | 数据库名 |
| `MYSQL_USERNAME` | root | 数据库用户 |
| `MYSQL_PASSWORD` | Password1! | 数据库密码（生产建议用环境变量覆盖） |
| `SERVER_PORT` | 9080 | 服务端口 |
| `R2_ENABLED` | false | 是否启用 Cloudflare R2 图片存储 |
| `R2_ENDPOINT` | （空） | R2 S3 兼容端点 |
| `R2_ACCESS_KEY_ID` | （空） | R2 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | （空） | R2 Secret Access Key |
| `R2_BUCKET` | （空） | R2 bucket 名称 |
| `R2_PUBLIC_BASE_URL` | （空） | 图片公开访问基础 URL |
| `R2_REGION` | auto | R2 区域，通常保持 `auto` |

示例（显式指定数据库与端口）：

```bash
export MYSQL_DATABASE=linear_lite
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=your_password
mvn spring-boot:run
```

如需启用任务描述图片上传，可在 `linear-lite-server/.env.properties` 中放入本地 R2 配置，Spring Boot 会在启动时自动加载：

```bash
cd linear-lite-server
mvn spring-boot:run
```

当前项目已使用环境变量方式读取 R2 配置；`.env.properties` 只是本机开发时的自动加载入口，不应提交到仓库。

## schema.sql 的用法

- **路径**：`src/main/resources/schema.sql` — 建表（含 `task_key`、`due_date`、`completed_at`、`task_attachments` 等）与种子数据（同一文件）。
- **已有旧库**：若表结构落后于当前 `schema.sql`，需自行 `ALTER` / 补表，或从 git 历史取已删除的增量脚本对照执行。

- **远程库 8.148.189.106（当前默认）**：

```bash
cd linear-lite-server/src/main/resources

# 1. 建库（若尚未创建）
mysql -h 8.148.189.106 -P 3306 -u root -p'Password1!' -e "CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;"

# 2. 建表 + 种子数据
mysql -h 8.148.189.106 -P 3306 -u root -p'Password1!' linear_lite < schema.sql
```

- **本地或其它主机**：将 `-h 8.148.189.106 -p'Password1!'` 换成对应主机与密码即可。种子 `INSERT` 使用 `ON DUPLICATE KEY UPDATE`，重复执行相对幂等。
