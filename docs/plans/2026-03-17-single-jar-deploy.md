# Single JAR 部署（前端打进 JAR）Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 `linear-lite-server` 目录执行 `mvn package` 即可打出包含前端静态资源的可执行 JAR；部署后通过同一端口访问前端与 API，SPA 路由刷新正常。

**Architecture:** Maven 使用 exec-maven-plugin 在 generate-resources 阶段于仓库根执行 npm ci + npm run build（使用系统 Node/npm），再用 maven-resources-plugin 将 `../dist` 拷贝到 `target/classes/static`。Spring Boot 默认提供 classpath:/static/；新增 WebMvcConfigurer 的 addResourceHandlers，对找不到静态文件的请求返回 index.html（SPA fallback）。不修改前端代码。

**Tech Stack:** Maven (frontend-maven-plugin, maven-resources-plugin), Spring Boot 3, Vue 3 / Vite（仅构建产物）

**Design:** `docs/plans/2026-03-17-single-jar-deploy-design.md`

---

### Task 1: Maven 前端构建与拷贝

**Files:**
- Modify: `linear-lite-server/pom.xml`

**Step 1: 添加 exec-maven-plugin（使用系统 Node）**

在 `<build><plugins>` 内、在现有 `spring-boot-maven-plugin` 之前添加：

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>npm ci</id>
            <phase>generate-resources</phase>
            <goals>
                <goal>exec</goal>
            </goals>
            <configuration>
                <executable>npm</executable>
                <arguments><argument>ci</argument></arguments>
                <workingDirectory>${project.basedir}/..</workingDirectory>
            </configuration>
        </execution>
        <execution>
            <id>npm run build</id>
            <phase>generate-resources</phase>
            <goals>
                <goal>exec</goal>
            </goals>
            <configuration>
                <executable>npm</executable>
                <arguments><argument>run</argument><argument>build</argument></arguments>
                <workingDirectory>${project.basedir}/..</workingDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>
```

说明：使用系统 PATH 的 npm，要求本机已安装 Node/npm。frontend-maven-plugin 在不运行 install-node-and-npm 时会查找自备 Node 导致失败，故改用 exec-maven-plugin。

**Step 2: 添加拷贝 dist 到 target/classes/static 的 resources 执行**

在同一个 `<plugins>` 内、exec-maven-plugin 之后添加：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <version>3.3.1</version>
    <executions>
        <execution>
            <id>copy-frontend</id>
            <phase>process-resources</phase>
            <goals>
                <goal>copy-resources</goal>
            </goals>
            <configuration>
                <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                <resources>
                    <resource>
                        <directory>../dist</directory>
                        <filtering>false</filtering>
                    </resource>
                </resources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Step 3: 验证 Maven 构建**

在仓库根目录执行（从 linear-lite-server 执行会先跑前端构建）：

```bash
cd linear-lite-server
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS；且 `target/classes/static/index.html` 存在，且包含前端资源（如 `target/classes/static/assets/` 下有 js/css）。

**Step 4: Commit**

```bash
git add linear-lite-server/pom.xml
git commit -m "build: frontend build and copy into JAR via Maven"
```

---

### Task 2: SPA 静态资源与 index.html 回退

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/config/WebConfig.java`

**Step 1: 实现 WebMvcConfigurer 并注册静态资源与 fallback**

- 让 `WebConfig` 实现 `WebMvcConfigurer`。
- 重写 `addResourceHandlers(ResourceHandlerRegistry registry)`：
  - 添加 `/**` 的 ResourceHandler，`addResourceLocations("classpath:/static/")`，`setResourceChain(true)`。
  - 使用 `addResolver` 添加自定义 `PathResourceResolver`：在 `resolveResourceInternal` 中先调用 `super.resolveResourceInternal(...)`；若返回 `null`，则再调用一次 `super.resolveResourceInternal(request, "index.html", locations, chain)` 并返回（即未命中静态文件时返回 index.html，供 Vue Router 使用）。
- 需添加的 import：`org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry`、`org.springframework.core.io.Resource`、`org.springframework.web.servlet.resource.PathResourceResolver`、`org.springframework.web.servlet.resource.ResourceResolverChain`、`jakarta.servlet.http.HttpServletRequest`、`java.util.List`。

示例实现（保留类中已有 CORS Bean）：

```java
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                        List<? extends Resource> locations, ResourceResolverChain chain) {
                    Resource resource = super.resolveResourceInternal(request, requestPath, locations, chain);
                    if (resource == null) {
                        resource = super.resolveResourceInternal(request, "index.html", locations, chain);
                    }
                    return resource;
                }
            });
}
```

**Step 2: 验证**

- 在 `linear-lite-server` 下执行 `mvn clean package -DskipTests`，确认 BUILD SUCCESS。
- 执行 `java -jar target/linear-lite-server-0.1.0-SNAPSHOT.jar`（需 MySQL 等已配置），浏览器访问 `http://localhost:8080/` 应看到前端；访问 `http://localhost:8080/projects` 并刷新，应仍为同一 SPA（不 404）。
- 登录、打开项目/任务等核心流程与开发环境一致。

**Step 3: Commit**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/config/WebConfig.java
git commit -m "feat(server): serve static frontend and SPA fallback to index.html"
```

---

### Task 3: 文档

**Files:**
- Modify: `README.md`
- Optionally: `linear-lite-server/README.md`

**Step 1: 在 README 中增加「单 JAR 部署」小节**

在「快速开始」之后或「仓库结构」之前增加一节，内容包含：

- 从 `linear-lite-server` 目录执行 `mvn clean package`（需本机已安装 Node.js 与 npm）。
- 运行：`java -jar target/linear-lite-server-0.1.0-SNAPSHOT.jar`。
- 浏览器访问 `http://localhost:8080/` 即可使用前端；API 仍为 `/api`，同源无需配置 `VITE_API_BASE_URL`。
- 注明：单 JAR 部署时无需设置 `VITE_API_BASE_URL`。

**Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add single JAR deployment section"
```

---

### 验收总结

- 在 `linear-lite-server` 下执行 `mvn clean package`，成功生成 JAR。
- `java -jar target/linear-lite-server-0.1.0-SNAPSHOT.jar` 后，访问 `http://localhost:8080/` 为前端，子路径刷新正常，API 同源工作。
