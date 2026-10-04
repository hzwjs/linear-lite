# Markdown 拖拽导入验收记录 V2

日期：2026-10-04

## 交付行为

- 文档目录树接收本地 `.md` 文件。拖到文档行时创建子文档，拖到空白区域时创建根文档；MD 按钮提供多文件选择入口。
- 文件名去除扩展名后成为标题；正文转换为 BlockNote 内容，沿用文档创建、编辑和保存流程。
- 图片引用保存为文字，HTML 块保存为 `html` 代码块，Mermaid 保存为 `mermaid` 代码块。导入 `.md` 不读取文件之外的图片，图片上传使用编辑器入口。
- 文件要求为 UTF-8，单文件上限 5 MiB。失败项保留文件与目标目录，重试成功项不创建副本；目录刷新失败提供刷新入口。

V1 记录保留。V2 将图片引用、HTML 块的行为从拒绝导入改为保留内容，并补充用户原文件在本地运行服务中的验收。

## 报错原因与处理

截图中的请求发送至 `localhost:9080`。该服务启动于 14:51:52，运行包内缺少 `ProjectDocumentImportController` 和 `MarkdownDocumentImportService`。使用登录页面发起同一路径的 multipart 请求，响应为 500，消息为 `Request method 'POST' is not supported`。

前端资源构建、后端测试与打包完成后，更新本地后端运行包。项目托管启动流程保持运行，状态检查显示前端 PID 28148、端口 5173，后端 PID 52118、端口 9080。上传接口返回文件类型校验错误 400，确认请求进入导入接口。

旧包备份位置：`linear-lite-server/target/local-backups/linear-lite-server-before-markdown-20261004-160723.jar`。

新包 SHA-256：`fbaa68a9adf3b48e513c8de7ba29b855fae34ec68e61134e3884c49a17195506`。

## 数据路径

`multipart .md 文件 → MarkdownToBlockNoteConverter.convertFile → ProjectDocumentCommandService.create → project_documents.content → ProjectDocumentResponse.content → 文档编辑器`

文件导入使用独立转换入口，MCP 的 `mcp-image` 资源绑定入口保留原契约。文档创建沿用项目成员权限、父文档归属校验、首版历史记录和索引事件；没有新增数据字段或数据库脚本。

## 验证结果

| 验证范围 | 结果 |
| --- | --- |
| 后端导入、HTTP 参数、文档创建、Markdown 转换 | 43 项测试通过，失败、错误、跳过数量均为 0 |
| 前端导入、目录树、导航、文档 API | 5 个测试文件、28 项测试通过；排除其他工作树 |
| Vite 生产资源构建 | 通过 |
| 后端打包 | 通过；运行包包含导入 Controller 和 Service |
| 全量 Vue 类型检查 | 受现有 `BlockNoteEditorReact.tsx:139` 的 Shiki 类型冲突阻断；本次没有变更该文件与相关依赖 |
| 本地运行状态 | 5173 前端、9080 后端运行；前端进程保留 |

使用 ego-browser，在用户登录的 `http://localhost:5173/projects/37/documents/609` 页面，通过 Chromium 原生文件拖拽事件向目录树空白区域送入源文件：

`/Users/huangzhiwen/Documents/work/02code/project/tmp/easygo/docs/业务梳理/注册业务梳理-v2/注册业务梳理-v2.md`

上传返回 200，创建 EasyGo 项目 37 的根文档 629，标题为 `注册业务梳理-v2`。页面进入新文档，目录树显示该文档，正文标题、段落和表格在编辑器中显示。

刷新页面后，使用登录状态读取文档 API，结果如下：

| 保存内容 | 结果 |
| --- | --- |
| 文档 ID / 项目 ID / 父文档 ID | 629 / 37 / null |
| 内容格式 / 版本 | BLOCKNOTE / 1 |
| 根块数量 | 36 |
| Mermaid 源码 | 3 段，与源文件逐段一致 |
| 图片引用 | 4 处，源文件中的路径出现在保存内容中 |
| HTML 源码块 | 6 段 |
| 图片资源数量 | 0；本次文件不包含图片二进制上传 |
| 目录树与当前路由 | 目录树包含 629，路由为 `/projects/37/documents/629` |
| 刷新后的 API 错误请求 | 0 |

源文件和 `linear-lite-server/.env` 的前后 SHA-256 比较结果一致。源文件 SHA-256 为 `e7fe60d4d5a34ca7198d262f1eb0562d8da84600457cf4c2a0df5362a829ccd2`。

截图：

- [原文件导入结果](./注册业务梳理真实导入%20V2.png)
- [刷新后目录与正文](./注册业务梳理刷新验证%20V2.png)

## 验证边界

- 文档持久化证据来自运行服务的上传响应、刷新页面和文档 API 回读。本次没有执行 SQL，没有使用 DBX 修改数据库。
- 本次更新本地后端，没有部署远程环境，没有提交代码；本地配置与无关改动保留。
- 原生 Chromium 文件事件通过验证；操作系统文件管理器到浏览器的跨应用鼠标拖拽没有人工验证。
- 子文档拖拽、多文件与失败重试的浏览器证据见 V1；V2 使用用户原文件验证根文档拖拽与保存。
- 图片引用和 HTML 源码保留不等同于图片渲染和 HTML 折叠交互。
