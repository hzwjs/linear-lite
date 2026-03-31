# 项目统计视图模块实现计划

> **给 Claude：** 必须使用 `superpowers:executing-plans` 按任务逐步执行本计划。

**目标：** 为项目新增独立统计页面，支持天/周/月/年维度查看任务趋势、多维度分布（状态/负责人/优先级）、项目全局快照及逾期统计，并为后续日报/周报生成提供完整数据支撑。

**方案概述：** 后端新增两个 analytics API（summary 聚合 + tasks 分页明细），前端新增独立路由页面与统计组件。趋势数据同时包含创建/完成/到期三个口径，无需切换。路由使用 `/analytics`，projectId 从 store 获取，与现有路由模式一致。

**技术栈：** Vue 3 + TypeScript + Pinia + Vitest；Spring Boot + MyBatis-Plus + MySQL；JUnit 5

---

**相关技能引用：** `@superpowers/test-driven-development`、`@superpowers/verification-before-completion`

### 任务 1：定义后端 DTO 与查询契约

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/AnalyticsQuery.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/AnalyticsSummaryResponse.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/TrendBucket.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/StatusCount.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/AssigneeCount.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/PriorityCount.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/ProjectSnapshot.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/TaskSnapshotItem.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/dto/TaskSnapshotPageResponse.java`
- 测试：`linear-lite-server/src/test/java/com/linearlite/server/service/AnalyticsServiceTest.java`

**步骤 1：编写失败测试**

```java
@Test
void shouldRejectUnsupportedGranularity() {
    AnalyticsQuery query = new AnalyticsQuery();
    query.setGranularity("hour");
    query.setProjectId(1L);
    query.setFrom("2026-03-01T00:00:00");
    query.setTo("2026-03-31T23:59:59");
    assertThrows(IllegalArgumentException.class, () -> analyticsService.validateQuery(query));
}

@Test
void shouldRejectFromAfterTo() {
    AnalyticsQuery query = new AnalyticsQuery();
    query.setGranularity("week");
    query.setProjectId(1L);
    query.setFrom("2026-03-31T00:00:00");
    query.setTo("2026-03-01T00:00:00");
    assertThrows(IllegalArgumentException.class, () -> analyticsService.validateQuery(query));
}
```

**步骤 2：运行测试并确认失败**

运行：`cd linear-lite-server && mvn -Dtest=AnalyticsServiceTest test`
预期：FAIL，`AnalyticsService` 或 `validateQuery` 尚不存在。

**步骤 3：编写最小实现**

```java
public void validateQuery(AnalyticsQuery query) {
    Set<String> validGranularity = Set.of("day", "week", "month", "year");
    if (!validGranularity.contains(query.getGranularity())) {
        throw new IllegalArgumentException("Unsupported granularity: " + query.getGranularity());
    }
    LocalDateTime from = LocalDateTime.parse(query.getFrom());
    LocalDateTime to = LocalDateTime.parse(query.getTo());
    if (from.isAfter(to)) {
        throw new IllegalArgumentException("from must be before to");
    }
}
```

**步骤 4：运行测试并确认通过**

运行：`cd linear-lite-server && mvn -Dtest=AnalyticsServiceTest test`
预期：PASS。

**步骤 5：提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/dto/ \
  linear-lite-server/src/test/java/com/linearlite/server/service/AnalyticsServiceTest.java
git commit -m "feat(analytics): 定义统计查询与响应 DTO"
```

### 任务 2：实现后端聚合服务（summary）

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/service/AnalyticsService.java`
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/mapper/AnalyticsMapper.java`
- 新增：`linear-lite-server/src/main/resources/mapper/AnalyticsMapper.xml`
- 修改：`linear-lite-server/src/main/resources/schema.sql`（补充索引，仅索引不加外键）
- 测试：`linear-lite-server/src/test/java/com/linearlite/server/service/AnalyticsServiceTest.java`

**步骤 1：编写失败测试**

```java
@Test
void shouldReturnWeeklyTrendWithThreeCounts() {
    AnalyticsQuery query = new AnalyticsQuery();
    query.setGranularity("week");
    query.setProjectId(1L);
    query.setFrom("2026-03-01T00:00:00");
    query.setTo("2026-03-31T23:59:59");
    AnalyticsSummaryResponse response = analyticsService.getSummary(1L, 100L, query);
    assertNotNull(response.getTrend());
    // 每个 bucket 同时有 createdCount, completedCount, dueCount
    response.getTrend().forEach(bucket -> {
        assertNotNull(bucket.getCreatedCount());
        assertNotNull(bucket.getCompletedCount());
        assertNotNull(bucket.getDueCount());
    });
}

@Test
void shouldReturnCurrentSnapshotIndependentOfDateRange() {
    // currentSnapshot 反映全局状态，不受 from/to 过滤
    AnalyticsQuery query = new AnalyticsQuery();
    query.setGranularity("day");
    query.setProjectId(1L);
    query.setFrom("2020-01-01T00:00:00");
    query.setTo("2020-01-02T00:00:00");
    AnalyticsSummaryResponse response = analyticsService.getSummary(1L, 100L, query);
    assertNotNull(response.getCurrentSnapshot());
    // totalCount 应反映项目全部任务，不会因历史区间而为零
}

@Test
void shouldReturnAssigneeAndPriorityBreakdown() {
    AnalyticsQuery query = new AnalyticsQuery();
    query.setGranularity("week");
    query.setProjectId(1L);
    query.setFrom("2026-03-01T00:00:00");
    query.setTo("2026-03-31T23:59:59");
    AnalyticsSummaryResponse response = analyticsService.getSummary(1L, 100L, query);
    assertNotNull(response.getAssigneeBreakdown());
    assertNotNull(response.getPriorityBreakdown());
}
```

**步骤 2：运行测试并确认失败**

运行：`cd linear-lite-server && mvn -Dtest=AnalyticsServiceTest test`
预期：FAIL，服务方法或 Mapper 查询缺失。

**步骤 3：编写最小实现**

```java
public AnalyticsSummaryResponse getSummary(Long projectId, Long userId, AnalyticsQuery query) {
    requireProjectMember(projectId, userId);
    validateQuery(query);
    ZoneId zone = ZoneId.systemDefault();
    String zoneId = zone.getId();

    // 趋势：每个桶同时统计 created/completed/due 三个口径
    List<TrendBucket> trend = analyticsMapper.selectTrend(projectId, query, zoneId);

    // 当前项目全局快照（不受 from/to 影响）
    ProjectSnapshot snapshot = analyticsMapper.selectProjectSnapshot(projectId);

    // 区间内各维度分布
    List<StatusCount> statusBreakdown = analyticsMapper.selectStatusBreakdown(projectId, query, zoneId);
    List<AssigneeCount> assigneeBreakdown = analyticsMapper.selectAssigneeBreakdown(projectId, query, zoneId);
    List<PriorityCount> priorityBreakdown = analyticsMapper.selectPriorityBreakdown(projectId, query, zoneId);

    return AnalyticsSummaryResponse.builder()
            .meta(new Meta(projectId, zoneId, query.getGranularity(), "MONDAY"))
            .trend(trend)
            .currentSnapshot(snapshot)
            .statusBreakdown(statusBreakdown)
            .assigneeBreakdown(assigneeBreakdown)
            .priorityBreakdown(priorityBreakdown)
            .build();
}
```

**Mapper SQL 关键点**（以 trend 为例）：

```xml
<!-- 按 granularity 分桶，同时统计三个口径 -->
<select id="selectTrend" resultType="TrendBucket">
    WITH buckets AS (
        -- 根据 granularity 生成时间桶序列
    )
    SELECT
        b.bucket_start,
        b.bucket_end,
        COALESCE(SUM(CASE WHEN t.created_at BETWEEN b.bucket_start AND b.bucket_end THEN 1 ELSE 0 END), 0) AS created_count,
        COALESCE(SUM(CASE WHEN t.completed_at BETWEEN b.bucket_start AND b.bucket_end THEN 1 ELSE 0 END), 0) AS completed_count,
        COALESCE(SUM(CASE WHEN t.due_date BETWEEN b.bucket_start AND b.bucket_end THEN 1 ELSE 0 END), 0) AS due_count
    FROM buckets b
    LEFT JOIN tasks t ON t.project_id = #{projectId}
    GROUP BY b.bucket_start, b.bucket_end
    ORDER BY b.bucket_start
</select>
```

**索引补充**（schema.sql，仅索引无外键）：

```sql
CREATE INDEX idx_tasks_project_completed_at ON tasks (project_id, completed_at);
CREATE INDEX idx_tasks_project_due_date ON tasks (project_id, due_date);
CREATE INDEX idx_tasks_project_assignee ON tasks (project_id, assignee_id);
CREATE INDEX idx_tasks_project_priority ON tasks (project_id, priority);
```

**步骤 4：运行测试并确认通过**

运行：`cd linear-lite-server && mvn -Dtest=AnalyticsServiceTest test`
预期：PASS。

**步骤 5：提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/AnalyticsService.java \
  linear-lite-server/src/main/java/com/linearlite/server/mapper/AnalyticsMapper.java \
  linear-lite-server/src/main/resources/mapper/AnalyticsMapper.xml \
  linear-lite-server/src/main/resources/schema.sql \
  linear-lite-server/src/test/java/com/linearlite/server/service/AnalyticsServiceTest.java
git commit -m "feat(analytics): 实现多维度聚合统计服务"
```

### 任务 3：实现任务明细分页端点

**涉及文件：**
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/service/AnalyticsService.java`
- 修改：`linear-lite-server/src/main/java/com/linearlite/server/mapper/AnalyticsMapper.java`
- 修改：`linear-lite-server/src/main/resources/mapper/AnalyticsMapper.xml`
- 测试：`linear-lite-server/src/test/java/com/linearlite/server/service/AnalyticsServiceTest.java`

**步骤 1：编写失败测试**

```java
@Test
void shouldReturnPaginatedTaskSnapshot() {
    AnalyticsQuery query = new AnalyticsQuery();
    query.setProjectId(1L);
    query.setFrom("2026-03-01T00:00:00");
    query.setTo("2026-03-31T23:59:59");
    TaskSnapshotPageResponse response = analyticsService.getTaskSnapshot(1L, 100L, query, 1, 50);
    assertNotNull(response.getItems());
    assertTrue(response.getPageSize() <= 200);
    assertTrue(response.getPage() >= 1);
}

@Test
void shouldCapPageSizeAt200() {
    AnalyticsQuery query = new AnalyticsQuery();
    query.setProjectId(1L);
    query.setFrom("2026-03-01T00:00:00");
    query.setTo("2026-03-31T23:59:59");
    TaskSnapshotPageResponse response = analyticsService.getTaskSnapshot(1L, 100L, query, 1, 500);
    assertEquals(200, response.getPageSize());
}
```

**步骤 2：运行测试并确认失败**
**步骤 3：编写最小实现**

```java
public TaskSnapshotPageResponse getTaskSnapshot(Long projectId, Long userId,
        AnalyticsQuery query, int page, int pageSize) {
    requireProjectMember(projectId, userId);
    validateQuery(query);
    int cappedPageSize = Math.min(pageSize, 200);
    int offset = (page - 1) * cappedPageSize;
    ZoneId zone = ZoneId.systemDefault();

    List<TaskSnapshotItem> items = analyticsMapper.selectTaskSnapshot(
            projectId, query, zone.getId(), cappedPageSize, offset);
    int total = analyticsMapper.countTaskSnapshot(projectId, query, zone.getId());

    return new TaskSnapshotPageResponse(items, total, page, cappedPageSize);
}
```

**步骤 4：运行测试并确认通过**
**步骤 5：提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/AnalyticsService.java \
  linear-lite-server/src/main/java/com/linearlite/server/mapper/AnalyticsMapper.java \
  linear-lite-server/src/main/resources/mapper/AnalyticsMapper.xml \
  linear-lite-server/src/test/java/com/linearlite/server/service/AnalyticsServiceTest.java
git commit -m "feat(analytics): 实现任务明细分页查询"
```

### 任务 4：暴露 analytics API 并接入权限校验

**涉及文件：**
- 新增：`linear-lite-server/src/main/java/com/linearlite/server/controller/AnalyticsController.java`
- 测试：`linear-lite-server/src/test/java/com/linearlite/server/controller/AnalyticsControllerTest.java`

**步骤 1：编写失败测试**

```java
@Test
void getSummaryShouldReturn400WhenFromAfterTo() throws Exception {
    mockMvc.perform(get("/api/analytics/summary")
            .param("projectId", "1")
            .param("granularity", "week")
            .param("from", "2026-03-31T00:00:00")
            .param("to", "2026-03-01T00:00:00"))
        .andExpect(status().isBadRequest());
}

@Test
void getTasksShouldReturnPaginatedResults() throws Exception {
    mockMvc.perform(get("/api/analytics/tasks")
            .param("projectId", "1")
            .param("from", "2026-03-01T00:00:00")
            .param("to", "2026-03-31T23:59:59")
            .param("page", "1")
            .param("pageSize", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray())
        .andExpect(jsonPath("$.data.total").isNumber());
}
```

**步骤 2：运行测试并确认失败**
**步骤 3：编写最小实现**

```java
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AnalyticsSummaryResponse>> getSummary(
            HttpServletRequest request, AnalyticsQuery query) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        AnalyticsSummaryResponse data = analyticsService.getSummary(query.getProjectId(), userId, query);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<TaskSnapshotPageResponse>> getTasks(
            HttpServletRequest request, AnalyticsQuery query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        TaskSnapshotPageResponse data = analyticsService.getTaskSnapshot(
                query.getProjectId(), userId, query, page, pageSize);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
```

**步骤 4：运行测试并确认通过**
**步骤 5：提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/controller/AnalyticsController.java \
  linear-lite-server/src/test/java/com/linearlite/server/controller/AnalyticsControllerTest.java
git commit -m "feat(analytics): 暴露统计聚合与任务明细 API"
```

### 任务 5：前端类型定义、API 封装与 Store

**涉及文件：**
- 新增：`src/types/analytics.ts`
- 新增：`src/services/api/analytics.ts`
- 新增：`src/store/analyticsStore.ts`
- 修改：`src/router/index.ts`

**步骤 1：编写类型定义**

```ts
// src/types/analytics.ts
export interface AnalyticsQuery {
  projectId: number
  granularity: 'day' | 'week' | 'month' | 'year'
  from: string
  to: string
}

export interface TrendBucket {
  bucketStart: string
  bucketEnd: string
  createdCount: number
  completedCount: number
  dueCount: number
}

export interface ProjectSnapshot {
  totalCount: number
  statusBreakdown: StatusCount[]
  overdueCount: number
}

export interface StatusCount {
  status: string
  count: number
}

export interface AssigneeCount {
  assigneeId: number | null
  assigneeName: string
  totalCount: number
  completedCount: number
  inProgressCount: number
}

export interface PriorityCount {
  priority: string
  count: number
}

export interface AnalyticsSummaryResponse {
  meta: {
    projectId: number
    timezone: string
    bucketUnit: string
    weekStartDay: string
  }
  trend: TrendBucket[]
  currentSnapshot: ProjectSnapshot
  statusBreakdown: StatusCount[]
  assigneeBreakdown: AssigneeCount[]
  priorityBreakdown: PriorityCount[]
}

export interface TaskSnapshotItem {
  taskKey: string
  title: string
  status: string
  priority: string
  assigneeId: number | null
  assigneeName: string
  createdAt: string
  completedAt: string | null
  dueDate: string | null
}

export interface TaskSnapshotPageResponse {
  items: TaskSnapshotItem[]
  total: number
  page: number
  pageSize: number
}
```

**步骤 2：编写 API 封装**

```ts
// src/services/api/analytics.ts
export const analyticsApi = {
  getSummary(query: AnalyticsQuery): Promise<AnalyticsSummaryResponse> {
    return api.get<ApiResponse<AnalyticsSummaryResponse>>('/analytics/summary', { params: query })
      .then(res => unwrap(res))
  },
  getTasks(query: AnalyticsQuery & { page?: number; pageSize?: number }): Promise<TaskSnapshotPageResponse> {
    return api.get<ApiResponse<TaskSnapshotPageResponse>>('/analytics/tasks', { params: query })
      .then(res => unwrap(res))
  }
}
```

**步骤 3：编写 Store**

```ts
// src/store/analyticsStore.ts — 分别管理 summary 和 tasks 的加载/错误状态
```

**步骤 4：添加路由**

```ts
// 新增路由条目（与 / 和 /tasks/:taskId 平级）
{ path: '/analytics', component: () => import('../views/AnalyticsView.vue') }
```

**步骤 5：提交**

```bash
git add src/types/analytics.ts src/services/api/analytics.ts src/store/analyticsStore.ts src/router/index.ts
git commit -m "feat(analytics): 前端类型、API、Store 与路由接入"
```

### 任务 6：实现统计页面与组件

**涉及文件：**
- 新增：`src/views/AnalyticsView.vue`
- 新增：`src/components/analytics/AnalyticsFilters.vue`
- 新增：`src/components/analytics/TrendChart.vue`
- 新增：`src/components/analytics/StatusBreakdownChart.vue`
- 新增：`src/components/analytics/AssigneeBreakdownChart.vue`
- 新增：`src/components/analytics/PriorityBreakdownChart.vue`
- 新增：`src/components/analytics/ProjectSnapshotCard.vue`
- 新增：`src/components/analytics/TaskSnapshotList.vue`
- 修改：`src/App.vue`（侧边栏加入统计入口）
- 修改：`src/i18n/messages/zh-CN.ts`
- 修改：`src/i18n/messages/en.ts`

**页面布局**:

```
┌─────────────────────────────────────────────────┐
│ [粒度选择: 天|周|月|年]  [日期范围选择]           │
├───────────────┬─────────────────────────────────┤
│ 当前项目快照   │         趋势图                   │
│ - 总任务 120  │  (折线: 创建/完成/到期)           │
│ - 逾期 7      │                                  │
│ - 状态饼图    │                                  │
├───────────────┴─────────────────────────────────┤
│  状态分布  │  负责人分布  │  优先级分布            │
├─────────────────────────────────────────────────┤
│  任务明细列表（天/周时显示，支持分页）             │
└─────────────────────────────────────────────────┘
```

**渲染规则**:

```ts
const showTrend = computed(() => granularity.value !== 'day')
const showTaskList = computed(() => granularity.value === 'day' || granularity.value === 'week')
```

**步骤 5：提交**

```bash
git add src/views/AnalyticsView.vue src/components/analytics/ \
  src/App.vue src/i18n/messages/zh-CN.ts src/i18n/messages/en.ts
git commit -m "feat(analytics): 统计视图页面与全部组件"
```

### 任务 7：端到端回归与验证

**验证命令：**

```bash
# 后端测试
cd linear-lite-server && mvn test

# 前端测试与构建
npm run test
npm run build
```

**验收检查清单：**

- [ ] 侧边栏 Analytics 入口可点击跳转
- [ ] 粒度切换正常，day 无趋势图，month/year 无任务列表
- [ ] trend 折线图同时显示创建/完成/到期三条线
- [ ] currentSnapshot 卡片显示全局状态，切换日期范围不变
- [ ] assigneeBreakdown 正确显示各负责人工作量
- [ ] priorityBreakdown 正确显示各优先级分布
- [ ] overdueCount 不包含已完成/已取消任务
- [ ] 任务列表分页正常，点击可跳转详情
- [ ] summary 和 tasks 请求互不阻断，单个失败不影响其他区域
- [ ] 周粒度按周一起始分桶

## 执行检查点

- 检查点 A（任务 1-3 完成后）：后端两个端点可单测验证，聚合数据与分页均正常。
- 检查点 B（任务 4-5 完成后）：接口可用，前端可请求并拿到结构化数据。
- 检查点 C（任务 6-7 完成后）：页面可用、规则正确、回归通过。

## 风险清单与约束

- 时区边界必须统一使用服务器时区，不允许前端自行推导周边界。
- 周粒度分桶使用 ISO 标准，周一为起始日。
- `completedAt` 和 `dueDate` 为空值时，对应 count 字段不计入。
- 禁止引入数据库外键，仅允许索引优化与应用层关系维护。
- `tasks` 端点 pageSize 上限 200，后端强制 cap。
