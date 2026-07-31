# 项目内容搜索性能优化方案

## 目标

将全局搜索收敛为独立搜索索引查询，搜索请求不再读取全部任务描述和项目文档 `content_json`，业务数据库不保存搜索内容副本。`ProjectContentSearch` Module 在用户可访问项目范围内完成关键词与语义混合召回，固定返回前 50 条结果，接口处理时间目标为 1 秒内。

## 架构

`ProjectContentSearch` Module 继续提供唯一的 `search(query, userId)` Interface，Implementation 使用一条固定数据路径：

```text
任务/文档变更
  -> project_content_search_index_jobs
  -> ProjectContentSearchIndexWorker
  -> ProjectContentSearchIndex Adapter
  -> Qdrant 全文索引 + 稠密向量 + 结果 payload

搜索请求
  -> 用户可访问 projectIds
  -> ProjectContentSearchIndex Adapter
  -> Qdrant 标题精准、正文精准、语义三路候选
  -> Java 候选去重、固定排序、截断
  -> ProjectContentSearchResponse
```

在线检索只访问权限数据和搜索索引，不访问 `tasks.description`、`project_documents.content_json`，也不建立关系型搜索投影表。该 Seam 隔离业务数据库与搜索实现，提升 Module 的 Depth；数据库替换只影响源记录读取、权限查询和索引任务 Adapter，不影响搜索 Interface、索引结构和排序规则。

## 索引模型

Qdrant 每个 point 使用 `(contentType, numericId)` 生成稳定身份，包含：

- `content_dense`：标题与正文生成的语义稠密向量。
- `titleLiteralTokens`：标题的可定位 Unicode 字符序列，建立 Qdrant `text` payload index。
- `bodyLiteralTokens`：正文的可定位 Unicode 字符序列，建立 Qdrant `text` payload index。
- 结果 payload：`contentType`、`numericId`、`resourceId`、`projectId`、`title`、`excerpt`、`sourceUpdatedAt`、`contentHash`。

Worker 先按 `Locale.ROOT` 转为小写，再把每个 Unicode code point 编码成独立 token：

```text
派单任务 -> u6d3e u5355 u4efb u52a1
派单     -> u6d3e u5355
```

查询词执行相同编码，并使用 Qdrant phrase match。两个字面字段固定使用 `whitespace` tokenizer、关闭二次小写、启用 `phrase_matching`，使连续字符序列匹配严格等价于现有大小写不敏感的 `String.contains`。运行版本固定为支持 phrase match 的 Qdrant 1.15 及以上。

任务描述或文档 `content_json` 只在索引 Worker 中被单条读取并转换为可见纯文本。Qdrant 只保存可搜索字符序列和短摘要，不保存原始 `content_json`，业务数据库不增加搜索副本；在线响应通过 `with_payload` 只读取结果字段，不返回两个字面索引字段。索引字段到响应字段只有一条固定映射，不增加旧字段兼容或多键回退。

## 索引写入

将现有语义索引任务统一为 `project_content_search_index_jobs`，作为任务和文档搜索索引变更的唯一入口。

1. 任务或文档创建、更新、归档、恢复、删除时，在同一数据库事务内 upsert 一条索引任务，消除提交后进程退出导致任务丢失的窗口。
2. Worker 按 `content_type + numeric_id` 读取唯一源记录，并执行一次纯文本提取。
3. 可搜索内容写入标题字符序列、正文字符序列、短摘要和语义稠密向量，完成一个 Qdrant point upsert。
4. 不可搜索内容按稳定 point 身份删除索引。
5. Worker 通过原子租约和任务代次领取任务；处理期间产生的新代次保留当前租约，禁止新旧 Worker 并发覆盖，Qdrant 成功后只完成当前代次。
6. Qdrant upsert/delete 使用确定性 point ID 和 `wait=true`；`contentHash` 未变化时直接完成任务，失败任务按重试时间继续处理。

`ProjectContentSearchIndex` Adapter 负责 Qdrant 数据结构和查询语法；业务身份、内容提取、可见性和删除语义由搜索 Module 统一决定，保持高 Locality。

## 在线检索

1. 将查询词去除首尾空白，长度限定为 1 至 200 个字符，并编码为与索引一致的 Unicode 字符序列。
2. 一次查询取得当前用户可访问项目的 `projectId`、项目标识和项目名称；三路 Qdrant 查询都必须使用 `projectIds` payload filter。
3. Qdrant 使用 phrase match 分别从 `titleLiteralTokens` 和 `bodyLiteralTokens` 召回精准子串候选，每组最多 50 条。
4. 同一查询词生成语义稠密向量，从 `content_dense` 召回最多 50 条语义候选。
5. Java 只合并不超过 150 条候选，不再读取正文或执行关键词匹配；按 `(contentType, numericId)` 去重。
6. 固定排序为：标题精准命中、正文精准命中、语义命中；精准结果同级按源更新时间倒序、数字主键正序，语义结果按相似度倒序。
7. 截取前 50 条，使用 point 结果 payload 映射资源字段，并从本次权限快照映射项目名称和标识后生成 `ProjectContentSearchResponse`。

搜索 Interface 不提供全量结果和无上限分页。权限为空时直接返回空集合；索引无结果时返回空集合，不回查源表。

## 数据库替换隔离

- 搜索正文、全文索引和语义向量均不进入业务数据库。
- `project_content_search_index_jobs` 只保存资源身份、操作、执行时间、代次、重试次数和租约，不保存正文。
- 数据库 Adapter 只实现“读取用户项目权限及项目元数据”“按身份读取单条可索引源记录”“与业务变更同事务写入索引任务”三个 Interface。
- 更换数据库时重写这三个 Adapter Implementation 和任务表 DDL，Qdrant 索引、在线搜索流程、排序规则及前端均保持不变。

## 代码调整

- `ProjectContentSearchService`：改为权限快照获取、候选合并、固定排序和结果映射的 Implementation，禁止正文匹配。
- `ProjectContentSearchMapper`：删除 `selectAccessibleContents` 及全量内容查询。
- `ProjectContentSearchIndex`：统一精准与语义搜索 Interface。
- `QdrantProjectContentSearchIndex`：统一维护全文 payload index、稠密向量、权限 filter 和三路候选查询，作为 Qdrant Adapter。
- `ProjectContentLiteralCodec`：集中实现标题、正文和查询词的 Unicode 字符序列编码，保证索引与查询完全同构。
- `ProjectContentSemanticIndexWorker`：收敛为统一索引 Worker，一次性构建完整 point，并使用租约和代次保证并发更新不丢失。
- `SearchableProjectContent`：仅用于单条源记录索引构建，不参与在线搜索。
- `schema.sql`：只归档索引任务表调整，不新增关系型搜索投影表。
- 前端搜索 Interface 保持 `/search?query=` 不变，按 1 字符最小长度发起请求。

## 迁移顺序

1. 将索引任务改为业务变更同事务持久化，并增加租约和代次字段。
2. 扩展 Qdrant collection 的字符序列全文索引、phrase match、稠密向量和权限字段索引。
3. 发布统一索引 Worker，执行任务和文档全量重建。
4. 切换在线检索到 `ProjectContentSearchIndex` 固定路径。
5. 删除全量内容查询及 Java 内存关键词匹配逻辑。

切换后不保留关系型搜索投影、源表全量检索或索引缺失回查路径。
