# 项目文档（Wiki）实施方案

## 目标

在不改变 Linear Lite 任务协作定位的前提下，为每个项目增加可分层组织、可编辑、可恢复、可搜索的文档空间。Wiki 能力只存在于项目上下文内，项目成员关系是唯一权限来源。

## 产品结构

项目工作区固定包含两个一级页签：

```text
项目
├── Issues
│   ├── Board
│   ├── List
│   └── Gantt
└── Documents
    ├── 项目内文档树
    └── 当前文档
```

路由使用稳定数据库 ID：

```text
/projects/:projectId/documents
/projects/:projectId/documents/:documentId
```

全局侧栏继续只展示项目。进入 Documents 后，内容区左侧显示当前项目的文档树，右侧显示文档正文；文档改名、移动不会改变 URL。

移动端不增加 Documents 一级入口。用户从全局搜索或任务中的文档链接进入只读文档页，保持移动端“短路径执行任务”的现有定位。

## 首次交付范围

- 项目内文档树：创建根文档、创建子文档、展开、折叠、同级排序、跨层移动。
- 文档编辑：标题、正文、代码块、图片、Mermaid、成员 Mention。
- 自动保存：标题和正文使用同一个版本号提交，服务端以乐观锁拒绝过期写入。
- 版本历史：每次成功保存生成一个不可变版本，可从历史版本恢复为新的当前版本。
- 归档与恢复：归档父文档时整体归档其子树，恢复时整体恢复原结构。
- 项目搜索：文档树内按标题过滤。
- 全局搜索：同时返回用户有权访问的任务和文档。
- 内容互链：任务描述和文档输入 `@` 时可选择当前项目文档，并插入稳定文档链接。

本次不增加全局 Wiki 空间、独立文件夹实体、页面级权限、公开分享、模板中心、多人实时光标、文档评论和外部 Wiki 导入。

## 领域规则

1. 每个文档必须属于一个项目，不能脱离项目存在。
2. 每个树节点都是文档；正文为空的文档可以承担目录页作用，不增加 folder 类型。
3. `parent_document_id = NULL` 表示项目根文档；父文档与子文档必须属于同一项目。
4. 文档移动时禁止把文档放入自身或任一后代节点。
5. 文档树不设置业务层级上限，避免数据层产生截断或兼容路径；界面按真实层级滚动展示。
6. 同一父节点下按 `sort_order` 升序、`id` 升序返回。移动后服务端重新生成该父节点下连续的 `sort_order`。
7. 归档和恢复以完整子树为单位，不把子文档静默提升到根节点。
8. 文档删除只在项目删除时执行；日常操作只归档，避免不可恢复的数据丢失。
9. 所有读取、搜索、写入、移动、归档和恢复都先校验当前用户的项目成员关系。
10. 文档正文只接受 BlockNote JSON 数组，不接收 HTML、Markdown 或纯文本兼容格式。

## 数据模型

### `project_documents`

```sql
CREATE TABLE IF NOT EXISTS project_documents (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
    parent_document_id BIGINT       DEFAULT NULL,
    title              VARCHAR(256) NOT NULL,
    content_json       LONGTEXT     NOT NULL,
    sort_order         INT          NOT NULL DEFAULT 0,
    version            BIGINT       NOT NULL DEFAULT 1,
    creator_id         BIGINT       NOT NULL,
    last_editor_id     BIGINT       NOT NULL,
    archived_at        DATETIME     DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_documents_project_parent_order
ON project_documents (project_id, parent_document_id, sort_order, id);

CREATE INDEX idx_project_documents_parent
ON project_documents (parent_document_id);

CREATE INDEX idx_project_documents_project_updated
ON project_documents (project_id, updated_at, id);
```

`content_json` 的空文档固定写为 BlockNote 空文档 JSON，不使用 `NULL`、空字符串或其它格式表达同一语义。

### `project_document_revisions`

```sql
CREATE TABLE IF NOT EXISTS project_document_revisions (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id   BIGINT       NOT NULL,
    version       BIGINT       NOT NULL,
    title         VARCHAR(256) NOT NULL,
    content_json  LONGTEXT     NOT NULL,
    editor_id     BIGINT       NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_document_revisions_document_version (document_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_document_revisions_document_created
ON project_document_revisions (document_id, created_at, id);
```

创建文档时同步写入版本 `1`。每次更新先通过 `id + expectedVersion` 乐观锁写入当前文档，再写入新版本快照。恢复历史版本不是覆盖旧版本，而是以历史内容生成一个更大的新版本。

数据库增量脚本落在 `linear-lite-server/src/main/resources/migration-add-project-documents.sql`，并在交付时完整合并进 `linear-lite-server/src/main/resources/schema.sql`，保证新库和旧库只有同一份最终表结构。

## 唯一数据路径

正文只走以下路径：

```text
project_documents.content_json
→ ProjectDocumentResponse.content
→ ProjectDocument.content
→ StructuredDocumentEditor.modelValue
```

- 不持久化 Markdown、HTML、纯文本正文副本。
- 搜索文本由 `content_json` 解析后写入搜索索引，搜索索引是派生数据，不作为文档读取来源。
- 历史版本只从 `project_document_revisions.content_json` 恢复，不尝试从搜索索引或浏览器缓存恢复。
- 文档父级只读取 `parent_document_id`，不增加 `path`、`ancestors`、`level` 等并行结构字段。
- 文档链接只使用 `documentId` 生成稳定路由，不通过标题、slug 或 URL 文本反查文档。

## 服务端 Module

### `ProjectAccessGuard`

新增统一项目权限 Module：

```java
void requireMember(Long projectId, Long userId)
void requireOwner(Long projectId, Long userId)
```

`TaskPermissionGuard` 保留任务定位职责，但项目成员校验委托给 `ProjectAccessGuard`。`ProjectService`、项目文档和项目搜索均使用相同入口，不再各自查询 `project_members`。

### `ProjectDocumentQueryService`

负责：

- 返回当前项目未归档文档的轻量树元数据。
- 按文档 ID 返回完整正文。
- 返回归档文档树。
- 返回指定文档的版本列表和版本正文。
- 所有查询先通过 `ProjectAccessGuard`。

树接口只返回 `id`、`projectId`、`parentDocumentId`、`title`、`sortOrder`、`version`、`updatedAt`，不携带正文，避免文档数量增长后把全部内容一次下发。

### `ProjectDocumentCommandService`

作为文档写操作的唯一入口，负责：

- 创建根文档或子文档。
- 校验 BlockNote JSON、标题长度和父文档项目归属。
- 通过 `expectedVersion` 更新标题和正文。
- 在同一事务中写当前文档与版本快照。
- 移动文档并重新排列原父节点与目标父节点下的顺序。
- 使用递归查询阻止循环移动。
- 归档、恢复完整子树。
- 发布文档搜索索引更新或删除事件。

### `ProjectLifecycleService`

项目删除从 `ProjectService.delete` 收敛到 `ProjectLifecycleService.deleteProject`。该 Module 在一个事务中按固定顺序清理项目文档版本、项目文档、任务关联数据、成员和项目本身，并在事务提交后发布任务与文档的搜索索引删除事件。

## 接口

### 文档树与正文

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/projects/{projectId}/documents/tree` | 获取未归档文档树元数据 |
| `GET` | `/api/projects/{projectId}/documents/archive` | 获取归档文档树元数据 |
| `POST` | `/api/projects/{projectId}/documents` | 创建根文档或子文档 |
| `GET` | `/api/project-documents/{documentId}` | 获取完整文档 |
| `PUT` | `/api/project-documents/{documentId}` | 更新标题与正文 |
| `PUT` | `/api/project-documents/{documentId}/position` | 移动与排序 |
| `POST` | `/api/project-documents/{documentId}/archive` | 归档子树 |
| `POST` | `/api/project-documents/{documentId}/restore` | 恢复子树 |

创建请求：

```json
{
  "parentDocumentId": 12,
  "title": "接口设计"
}
```

更新请求：

```json
{
  "expectedVersion": 7,
  "title": "接口设计",
  "content": "[]"
}
```

移动请求：

```json
{
  "parentDocumentId": 18,
  "previousSiblingId": 23
}
```

`previousSiblingId = NULL` 表示移动到目标父节点的第一位。服务端只接受目标父节点和前一个兄弟节点，不接受前端提交完整 `sortOrder`，排序事实由服务端生成。

更新成功返回新的完整文档和新版本号。`expectedVersion` 不等于数据库当前版本时返回 `409 Conflict` 和当前版本号，不自动合并、不覆盖服务端内容。

### 版本历史

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/project-documents/{documentId}/revisions` | 获取版本元数据列表 |
| `GET` | `/api/project-documents/{documentId}/revisions/{version}` | 获取指定版本正文 |
| `POST` | `/api/project-documents/{documentId}/revisions/{version}/restore` | 恢复为新的当前版本 |

恢复请求携带 `expectedVersion`，与普通更新共用同一个乐观锁规则。

## 文档树交互

`DocumentsView` 使用左右分栏：

- 左栏宽度默认 `280px`，包含 Documents 标题、新建根文档按钮、标题过滤框和树。
- 每个节点显示展开按钮、文档图标、单行标题和悬浮操作菜单。
- 节点菜单只提供“新建子文档”“归档”。改名直接在文档正文标题处完成。
- 拖拽时在目标行上方、内部、下方显示明确落点；分别对应同级前置、成为子文档、同级后置。
- 折叠状态按 `projectId + documentId` 保存在本地，只保存展示偏好，不保存业务结构。
- 进入 `/documents` 时打开最近更新的根文档；项目无文档时展示创建第一篇文档的空态。
- 当前文档被移动后只更新树，不改变详情路由。
- 当前文档被归档后返回同一项目的 Documents 根路由。

正文区保持文档式阅读布局：最大正文宽度 `860px`，标题与正文处于同一纵向阅读流；顶部只保留面包屑、保存状态、历史和归档入口，不复制任务属性面板。

## 前端 Module

### `StructuredDocumentEditor`

将现有 `BlockNoteEditorWrapper.vue` 深化为通用编辑器 Module，唯一 Interface 包含：

```ts
type StructuredDocumentEditorProps = {
  modelValue: string
  readonly: boolean
  mentionMembers: Array<{ id: number; label: string }>
  mentionDocuments: Array<{ id: number; title: string; projectId: number }>
}
```

任务描述和项目文档都使用该 Module。图片继续通过现有 `/api/uploads/images` 上传；编辑器内部负责 BlockNote JSON 序列化，调用方不解析编辑器实现细节。

文档 `@` 建议项只加载当前项目文档，选中后插入指向 `/projects/{projectId}/documents/{documentId}` 的标准链接节点。链接文本使用插入当时的标题，路由目标只由 `documentId` 决定。

### `documentStore`

状态固定分为：

```text
treeNodes            当前项目文档树元数据
activeDocument       当前完整文档
activeRevision       当前查看的历史版本
saveState            idle | dirty | saving | saved | conflict | failed
archivedTreeNodes    仅打开归档视图时加载
```

Store 负责加载树、加载正文、创建、移动、归档、恢复和自动保存。树节点与正文实体分开缓存，禁止用树接口的轻量节点替代完整文档。

自动保存采用单一串行管线：

1. 标题或正文变化后标记 `dirty`。
2. 停止输入 `800ms` 后提交当前 `expectedVersion`。
3. 请求进行中产生的新修改留在下一次待提交快照中。
4. 成功后使用响应中的完整文档和版本号更新基线。
5. 收到 `409` 后停止后续写入并进入 `conflict`，保留本地草稿；用户只能复制草稿或重新加载服务端版本，不执行字段合并和覆盖写入。

### 页面与代码落点

- `src/views/DocumentsView.vue`：项目 Documents 页面容器。
- `src/components/documents/DocumentTree.vue`：树渲染、展开和键盘导航。
- `src/components/documents/DocumentTreeNode.vue`：单节点操作与拖拽落点。
- `src/components/documents/DocumentEditor.vue`：标题、正文、保存状态和文档操作。
- `src/components/documents/DocumentHistoryPanel.vue`：版本列表、预览和恢复。
- `src/store/documentStore.ts`：文档状态与串行保存管线。
- `src/services/api/documents.ts`：文档接口映射。
- `src/types/document.ts`：文档、树节点、版本类型。
- `src/router/index.ts`：Documents 列表、详情和移动端只读路由。
- `src/i18n/messages/zh-CN.ts`、`src/i18n/messages/en.ts`：全部文档界面文案。

## 搜索深化

现有任务专用搜索深化为 `ProjectContentSearch` Module，任务与文档共用一个搜索 Interface：

```java
List<ProjectContentSearchResult> search(String query, Long userId)
```

搜索结果固定结构：

```json
{
  "contentType": "document",
  "resourceId": "42",
  "projectId": 3,
  "title": "部署手册",
  "excerpt": "..."
}
```

`contentType` 只允许 `task`、`document`；任务 `resourceId` 使用 `task_key`，文档使用十进制 `id` 字符串。前端根据 `contentType` 走唯一对应路由，不尝试猜测类型。

将 `task_semantic_index_jobs` 迁移为通用 `content_semantic_index_jobs`，唯一键为 `(content_type, resource_id)`。Qdrant 使用新的项目内容 collection，payload 固定包含 `contentType`、`resourceId`、`projectId`。部署后从数据库全量重建任务与文档索引，只读取新 collection，不保留新旧 collection 双读。

接口调整为：

```text
GET /api/search?query={query}
```

前端将 `GlobalTaskSearchModal.vue` 重命名为 `GlobalSearchModal.vue`，按任务、文档展示不同图标和元信息；权限过滤仍以当前用户所属 `projectId` 集合为唯一条件。

## 服务端代码落点

- `entity/ProjectDocument.java`、`ProjectDocumentRevision.java`
- `mapper/ProjectDocumentMapper.java`、`ProjectDocumentRevisionMapper.java`
- `dto/document/*`：树节点、正文、创建、更新、移动、版本 DTO。
- `controller/ProjectDocumentController.java`
- `service/ProjectAccessGuard.java`
- `service/ProjectDocumentQueryService.java`
- `service/ProjectDocumentCommandService.java`
- `service/ProjectLifecycleService.java`
- `service/ProjectContentSearchService.java`
- `service/ProjectContentTextExtractor.java`
- `service/ContentSemanticIndexQueueService.java`
- `schema.sql` 与 `migration-add-project-documents.sql`

`ProjectContentTextExtractor` 只接受合法 BlockNote JSON；非法正文作为写入错误在文档更新时拒绝，不在搜索阶段增加 HTML、Markdown 或纯文本回退解析。

## 实施顺序

1. 新增文档与版本表，将增量 DDL 合并进 `schema.sql`。
2. 提取 `ProjectAccessGuard`，让项目、任务和文档权限共用同一成员判断入口。
3. 完成文档树查询、正文查询、创建、乐观锁更新和版本快照。
4. 完成移动排序、循环校验、子树归档与恢复。
5. 收敛项目删除到 `ProjectLifecycleService`，纳入文档及版本清理。
6. 深化 BlockNote 包装层为 `StructuredDocumentEditor`，保持任务描述原数据格式不变。
7. 完成 Documents 路由、左右分栏页面、文档树和正文自动保存。
8. 完成版本历史面板与冲突处理界面。
9. 增加当前项目文档 `@` 建议并插入稳定文档链接。
10. 将任务语义搜索一次性迁移为项目内容搜索，重建统一索引并替换全局搜索界面。
11. 增加移动端文档只读页以及任务链接、搜索结果的导航处理。

现有任务描述不自动转换为文档、不复制到文档、不双写。项目文档上线后，任务继续保存执行上下文，长期规格、决策和手册由用户明确创建到 Documents。
