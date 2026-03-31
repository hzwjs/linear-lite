# 项目统计视图模块设计说明

**日期**: 2026-03-31
**状态**: v2 — 全面修订（扩充维度、拆分 API、路由对齐、日报/周报导向）

---

## 1. 背景与目标

- **目标**: 在项目维度新增统计视图，支持按天/周/月/年查看任务进展，并为后续自动生成日报/周报提供数据基础。
- **关键要求**:
  - 统计页使用独立路由，与 Board/List 视图平级。
  - 一次查询可同时获得多时间口径数据（创建数、完成数、到期数），无需反复切换。
  - 除趋势外，提供按负责人、优先级、状态的多维度分布，以及逾期统计。
  - 提供"当前项目全局快照"，反映截至当前的任务总体状况。
  - 时间边界按服务器时区计算，周粒度默认 ISO 标准（周一起始）。

---

## 2. 方案选择

### 2.1 备选方案

- **A 前端聚合**：前端拉取任务后本地分桶统计（开发快，但口径一致性与性能风险高）。
- **B 后端聚合 API（选定）**：后端按查询参数聚合并返回结构化结果（口径统一、扩展性好）。
- **C 混合方案**：趋势/分布后端聚合，任务列表复用现有列表接口（实现折中但维护复杂）。

### 2.2 选型结论

采用 **B 后端聚合 API**。核心原因：

- 服务器时区口径统一，避免前端本地时区导致跨天/跨周偏差。
- 大项目数据量下避免全量任务下发，性能更可控。
- 后续扩展指标（环比、燃尽图）无需重构前端聚合逻辑。

---

## 3. 架构与模块边界

### 3.1 前端

- 新增路由页面：`/analytics`（projectId 从 `projectStore.activeProjectId` 获取，与现有路由模式一致）
- 新增页面容器：`src/views/AnalyticsView.vue`
- 新增组件目录：`src/components/analytics/`
  - `AnalyticsFilters.vue` — 粒度、日期范围选择
  - `TrendChart.vue` — 趋势折线/柱状图
  - `StatusBreakdownChart.vue` — 状态分布饼图/条形图
  - `AssigneeBreakdownChart.vue` — 负责人工作量分布
  - `PriorityBreakdownChart.vue` — 优先级分布
  - `ProjectSnapshotCard.vue` — 当前项目全局状态概览
  - `TaskSnapshotList.vue` — 区间内任务明细（可分页）
- 新增 API 封装：`src/services/api/analytics.ts`
- 新增状态管理：`src/store/analyticsStore.ts`
- 新增类型定义：`src/types/analytics.ts`

### 3.2 后端

- 新增控制器：`AnalyticsController`（路径 `/api/analytics`）
- 新增服务：`AnalyticsService`
- 新增查询层：`AnalyticsMapper`
- 新增 DTO：
  - `AnalyticsQuery` — 查询参数
  - `AnalyticsSummaryResponse` — 聚合统计响应
  - `TrendBucket` — 趋势分桶
  - `StatusCount` — 状态分布项
  - `AssigneeCount` — 负责人分布项
  - `PriorityCount` — 优先级分布项
  - `ProjectSnapshot` — 项目全局快照
  - `TaskSnapshotItem` — 任务明细项

---

## 4. 数据流与接口约定

### 4.1 API 拆分原则

将统计数据拆为两个独立端点：

| 端点 | 职责 | 分页 |
|------|------|------|
| `GET /api/analytics/summary` | 趋势 + 各维度分布 + 全局快照 + 逾期统计 | 否 |
| `GET /api/analytics/tasks` | 区间内任务明细列表 | 是 |

拆分理由：
- `summary` 是聚合数据，不需要分页，适合一次性返回。
- `tasks` 是明细数据，大项目可能有大量任务，必须分页。
- 前端某个区域出错时可独立重试，不影响其他区域。

### 4.2 端点一：聚合统计

**请求**: `GET /api/analytics/summary`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectId` | Long | 是 | 项目 ID |
| `granularity` | String | 是 | `day \| week \| month \| year` |
| `from` | String | 是 | ISO 日期时间，区间起点 |
| `to` | String | 是 | ISO 日期时间，区间终点 |

**响应结构**:

```json
{
  "code": 200,
  "data": {
    "meta": {
      "projectId": 1,
      "timezone": "Asia/Shanghai",
      "bucketUnit": "week",
      "weekStartDay": "MONDAY"
    },
    "trend": [
      {
        "bucketStart": "2026-03-02T00:00:00",
        "bucketEnd": "2026-03-08T23:59:59",
        "createdCount": 12,
        "completedCount": 8,
        "dueCount": 3
      }
    ],
    "currentSnapshot": {
      "totalCount": 120,
      "statusBreakdown": [
        { "status": "todo", "count": 30 },
        { "status": "in_progress", "count": 45 },
        { "status": "done", "count": 40 },
        { "status": "canceled", "count": 5 }
      ],
      "overdueCount": 7
    },
    "statusBreakdown": [
      { "status": "todo", "count": 15 },
      { "status": "in_progress", "count": 20 }
    ],
    "assigneeBreakdown": [
      { "assigneeId": 1, "assigneeName": "Alice", "totalCount": 10, "completedCount": 6, "inProgressCount": 4 }
    ],
    "priorityBreakdown": [
      { "priority": "urgent", "count": 3 },
      { "priority": "high", "count": 12 }
    ]
  }
}
```

**语义说明**：

- **`trend[]`**：每个分桶同时包含 `createdCount`（该桶内新建数）、`completedCount`（该桶内完成数）、`dueCount`（该桶内到期数）。不再需要 `timeBasis` 切换。
- **`currentSnapshot`**：截至当前时刻的项目全局状态，不受 `from/to` 过滤影响。包含总任务数、各状态分布、逾期数（`dueDate < now && status not in (done, canceled)`）。
- **`statusBreakdown[]`**：查询区间 `[from, to]` 内存在的任务（`createdAt` 落在区间内 OR `completedAt` 落在区间内 OR 在区间内处于活跃状态）的状态分布。
- **`assigneeBreakdown[]`**：查询区间内任务按负责人聚合，包含各人的总数、完成数、进行中数。
- **`priorityBreakdown[]`**：查询区间内任务按优先级聚合。

### 4.3 端点二：任务明细

**请求**: `GET /api/analytics/tasks`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `projectId` | Long | 是 | 项目 ID |
| `from` | String | 是 | ISO 日期时间 |
| `to` | String | 是 | ISO 日期时间 |
| `page` | Integer | 否 | 页码，默认 1 |
| `pageSize` | Integer | 否 | 每页条数，默认 50，最大 200 |

**响应结构**:

```json
{
  "code": 200,
  "data": {
    "items": [
      {
        "taskKey": "ENG-42",
        "title": "Fix login bug",
        "status": "done",
        "priority": "high",
        "assigneeId": 1,
        "assigneeName": "Alice",
        "createdAt": "2026-03-15T10:30:00",
        "completedAt": "2026-03-16T14:20:00",
        "dueDate": "2026-03-17T00:00:00"
      }
    ],
    "total": 87,
    "page": 1,
    "pageSize": 50
  }
}
```

**后端自动控制**：当 `granularity` 为 `month` 或 `year` 时，前端不请求此端点（由前端逻辑控制，后端不做限制，保持端点通用性）。

### 4.4 前端渲染规则

| 粒度 | 趋势图 | 当前快照 | 各维度分布 | 任务明细 |
|------|--------|----------|-----------|---------|
| day | - | ✓ | ✓ | ✓ |
| week | ✓ | ✓ | ✓ | ✓ |
| month | ✓ | ✓ | ✓ | - |
| year | ✓ | ✓ | ✓ | - |

- 切换粒度/日期后，`summary` 和 `tasks`（如需）并行请求。
- 支持跳转已有任务详情路由（`/tasks/:taskKey`）。

---

## 5. 时间口径与边界规则

- 分桶边界全部由后端按服务器时区计算。
- `week` 粒度默认 ISO 标准：**周一**为一周起始日。`meta.weekStartDay` 字段明确返回。
- `trend` 每个桶同时统计三个口径，无需前端切换：
  - `createdCount`：`createdAt` 落在桶区间内的任务数。
  - `completedCount`：`completedAt` 落在桶区间内的任务数。
  - `dueCount`：`dueDate` 落在桶区间内的任务数。
- `currentSnapshot.overdueCount`：`dueDate < now AND status NOT IN ('done', 'canceled', 'duplicate')` 的任务数，不受查询区间影响。

---

## 6. 异常处理

- 参数非法（如 `from > to`、不支持的 granularity 值）返回 `400` + 统一错误码（`ANALYTICS_INVALID_QUERY`）。
- 项目不存在或无权限返回 `404/403`，语义与现有任务接口对齐。
- 无数据返回空数组 / 零值而非错误。
- 前端对 `summary` 和 `tasks` 分别处理错误态与重试，互不阻断。

---

## 7. 性能与可维护性

- 聚合在数据库端完成（GROUP BY + COUNT），避免前端全量计算。
- 索引建议（仅索引，无外键）：
  - `(project_id, created_at)`
  - `(project_id, completed_at)`
  - `(project_id, due_date)`
  - `(project_id, status)`
  - `(project_id, assignee_id)`
  - `(project_id, priority)`
- `tasks` 端点强制分页，默认 50 条，上限 200 条。
- 前端按查询参数做短期缓存（同参数 30s 内不重复请求）。

---

## 8. 日报/周报数据支撑对照

下面是日报/周报典型内容与本模块数据来源的映射，确保所有所需数据都有接口支撑：

| 报告内容 | 数据来源 |
|---------|---------|
| 今日/本周新增任务数 | `trend[].createdCount` 求和 |
| 今日/本周完成任务数 | `trend[].completedCount` 求和 |
| 今日/本周到期任务数 | `trend[].dueCount` 求和 |
| 净增量（新增 - 完成） | 前端计算 |
| 当前总任务数 | `currentSnapshot.totalCount` |
| 当前各状态分布 | `currentSnapshot.statusBreakdown[]` |
| 当前逾期任务数 | `currentSnapshot.overdueCount` |
| 各负责人工作量 | `assigneeBreakdown[]` |
| 各优先级分布 | `priorityBreakdown[]` |
| 具体任务明细 | `GET /api/analytics/tasks` |

---

## 9. 测试与验收

### 9.1 后端测试

- 各粒度分桶边界正确（重点：week ISO 周一起始 + 服务器时区）。
- trend 同时返回三个 count 字段且各自正确。
- currentSnapshot 不受 from/to 影响，反映全局状态。
- overdueCount 正确排除已完成/已取消任务。
- assigneeBreakdown / priorityBreakdown 聚合正确。
- tasks 端点分页正确，边界条件（page 超范围、pageSize 超限）。
- 空数据、非法参数、权限场景行为正确。

### 9.2 前端测试

- 粒度切换后模块显隐符合规则（month/year 无任务明细）。
- summary 和 tasks 并行请求，互不阻断。
- 错误态/空态渲染正确，单区域出错不影响其他区域。
- 查询参数正确透传。

### 9.3 联调验收

- 相同查询条件下刷新结果一致。
- 服务器时区配置变化后，分桶按服务器规则变化且前端稳定展示。
- 各维度数据交叉验证一致（如 assigneeBreakdown 各人 totalCount 之和 ≈ statusBreakdown 各状态 count 之和）。
