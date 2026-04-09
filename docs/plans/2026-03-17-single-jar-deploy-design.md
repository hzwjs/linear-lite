# 前端打包进 JAR 单机部署 — 设计说明

**目标**：在 `linear-lite-server` 目录执行一条命令（`mvn package`）即可打出包含前端静态资源的可执行 JAR，部署后通过同一端口访问前端与 API。

**约束**：由 Maven 驱动前端构建（frontend-maven-plugin），不新增仓库根目录脚本；前端不修改 baseURL；同源部署，无需 CORS 变更。

---

## 1. 构建流程与 Maven 配置

- **执行入口**：在 `linear-lite-server` 目录执行 `mvn package`（或 `mvn clean package`）。不修改仓库根目录，不新增根目录脚本。
- **frontend-maven-plugin**：在 `linear-lite-server/pom.xml` 中配置，`workingDirectory` 指向 `..`（仓库根）。在 `generate-resources` 阶段依次执行：
  - 安装依赖：`npm ci`（优先，保证可复现）或 `npm install`；
  - 构建：`npm run build`。
  要求本机已安装 Node.js 和 npm；不内嵌 Node，避免 pom 臃肿和网络问题。
- **拷贝前端产物**：用 Maven 的 `resources` 插件在 `process-resources` 阶段（在 frontend-maven-plugin 之后）把 `../dist` 下的全部内容拷贝到 `target/classes/static`。不拷贝到 `src/main/resources/static`，避免构建产物进入版本库。
- **结果**：打出的 JAR 内包含 `BOOT-INF/classes/static/`（即 index.html、assets/ 等），与 Spring Boot 默认静态资源约定一致。

## 2. Spring Boot 静态资源与 SPA 回退

- **静态资源**：不新增配置。Spring Boot 默认提供 `classpath:/static/`，JAR 内 `BOOT-INF/classes/static/` 下的 `index.html`、`assets/*` 等通过根路径 `/` 访问。
- **API 路径**：保持现有 `/api` 前缀，与前端 `baseURL = '/api'` 一致；部署后同源，无需改前端或 CORS。
- **SPA 回退**：Vue Router 使用 history 模式，刷新或直接打开 `/projects/123` 等路径时，应由服务端返回 `index.html`。实现：`WebMvcConfigurer` 里 `addResourceHandlers` 注册 `/**`，使用 `resourceChain(true)` 与 fallback `ResourceResolver`，当找不到静态文件时返回 `index.html`；保证只处理非 `/api` 的 GET，静态资源优先。
- **顺序**：静态资源优先；找不到再走 SPA fallback。不改变现有 API 行为。

## 3. 前端 baseURL 与生产环境

- **不改前端构建默认**：仍使用 `baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api'`。打 JAR 时不设 `VITE_API_BASE_URL`，请求相对路径 `/api`，与同 JAR 后端同源。
- **Vite base**：保持 `base: '/'`，部署在根路径 `/`。
- **结论**：前端无需为「打进 JAR、同源部署」做任何修改；README 中补充：单 JAR 部署时无需配置 `VITE_API_BASE_URL`。

## 4. 异常与验收

- **构建时**：未安装 Node/npm 或 `npm run build` 失败时，Maven 构建失败，不产生残缺 JAR。不做「无 Node 时跳过前端」的可选逻辑。
- **拷贝时**：若 `../dist` 不存在，resources 拷贝失败，整个 `package` 失败。
- **验收**：
  1. 在 `linear-lite-server` 下执行 `mvn clean package`，成功生成 JAR。
  2. 执行 `java -jar target/linear-lite-server-0.1.0-SNAPSHOT.jar`，浏览器访问 `http://localhost:9080/` 看到前端；访问子路径并刷新仍为 SPA。
  3. 登录、项目/任务等核心流程与开发环境一致。
- **文档**：README（及可选的 linear-lite-server/README）增加「单 JAR 部署」小节：从 `linear-lite-server` 执行 `mvn clean package`、运行 JAR、访问 `http://host:9080/`，并注明需预装 Node/npm。
