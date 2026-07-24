# Daily Summary Email Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh the daily summary email so it uses a lighter modern visual style, renders translated statuses and real task progress, and never emits broken CTA/task links.

**Architecture:** Keep the current mail composition path intact and make a focused upgrade inside the existing DTO/query/composer boundary. Extend the digest task DTO with `progressPercent`, pass it through the query mapper unchanged, then rebuild the HTML/text rendering in `DigestMailComposer` around a normalized base URL helper and explicit status/progress presentation.

**Tech Stack:** Java 21, Spring Boot, MyBatis annotations, JUnit 5, Mockito

## Global Constraints

- Do not introduce a new template engine.
- Do not change dispatch, scheduling, or mail sending behavior.
- Use the approved status mapping exactly: `backlog -> 待规划`, `todo -> 待处理`, `in_progress -> 进行中`, `in_review -> 待审核`, `done -> 已完成`, `canceled -> 已取消`, `duplicate -> 重复任务`.
- Show `progressPercent` as the source of truth; render `--` when null.
- Footer CTA target must be `<baseUrl>/` and task target must be `<baseUrl>/projects/{projectId}/tasks/{taskKey}` when `app.public-base-url` is configured.
- When `app.public-base-url` is blank, do not emit broken clickable links in HTML or text output.
- Keep the visual tone young, modern, bright, lightweight, and not oppressive.

---

### Task 1: Carry `progressPercent` Through the Daily Summary Query Path

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java`

**Interfaces:**
- Consumes: `TaskMapper.selectDueForDigest(List<Long> projectIds, LocalDateTime endOfToday)`
- Produces: `DailySummaryTaskDto#getProgressPercent(): Integer` and `DailySummaryTaskDto#setProgressPercent(Integer): void`

- [ ] **Step 1: Write the failing DTO/query test expectation**

```java
@Test
void findDueTasksPreservesProgressPercentAndClassifiesTodayAndOverdue() {
    LocalDateTime startOfToday = LocalDate.of(2026, 7, 24).atStartOfDay();
    LocalDateTime endOfToday = LocalDate.of(2026, 7, 25).atStartOfDay();

    DailySummaryTaskDto today = new DailySummaryTaskDto();
    today.setTaskId(1L);
    today.setTaskKey("ENG-1");
    today.setTitle("今天到期");
    today.setProjectId(10L);
    today.setAssigneeId(7L);
    today.setAssigneeEmail("a@example.com");
    today.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
    today.setProgressPercent(65);

    DailySummaryTaskDto overdue = new DailySummaryTaskDto();
    overdue.setTaskId(2L);
    overdue.setTaskKey("ENG-2");
    overdue.setTitle("已逾期");
    overdue.setProjectId(10L);
    overdue.setAssigneeId(7L);
    overdue.setAssigneeEmail("a@example.com");
    overdue.setDueDate(LocalDateTime.of(2026, 7, 20, 12, 0));
    overdue.setProgressPercent(null);

    when(taskMapper.selectDueForDigest(anyList(), any(LocalDateTime.class)))
            .thenReturn(List.of(today, overdue));

    List<DailySummaryTaskDto> result = service.findDueTasks(List.of(10L), startOfToday, endOfToday);

    assertEquals(65, result.stream().filter(t -> t.getTaskId().equals(1L)).findFirst().orElseThrow().getProgressPercent());
    assertEquals(null, result.stream().filter(t -> t.getTaskId().equals(2L)).findFirst().orElseThrow().getProgressPercent());
    assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(1L) && !t.getOverdue()));
    assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(2L) && t.getOverdue()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd linear-lite-server && rtk proxy mvn -q -Dtest=DailySummaryQueryServiceTest test`
Expected: FAIL with missing `setProgressPercent` / `getProgressPercent` methods on `DailySummaryTaskDto`.

- [ ] **Step 3: Write the minimal DTO and mapper implementation**

```java
public class DailySummaryTaskDto {
    private Long taskId;
    private String taskKey;
    private String title;
    private String status;
    private String priority;
    private Long projectId;
    private Long assigneeId;
    private String assigneeUsername;
    private String assigneeEmail;
    private Integer progressPercent;
    private LocalDateTime dueDate;
    private Boolean overdue;

    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
}
```

```java
@Select("""
        <script>
        SELECT
          t.id AS taskId,
          t.task_key AS taskKey,
          t.title AS title,
          t.status AS status,
          t.priority AS priority,
          t.project_id AS projectId,
          t.assignee_id AS assigneeId,
          u.username AS assigneeUsername,
          u.email AS assigneeEmail,
          t.progress_percent AS progressPercent,
          t.due_date AS dueDate
        FROM tasks t
        JOIN users u ON u.id = t.assignee_id
        WHERE t.project_id IN
        <foreach collection="projectIds" item="pid" open="(" separator="," close=")">
          #{pid}
        </foreach>
          AND t.due_date IS NOT NULL
          AND t.due_date &lt; #{endOfToday}
          AND LOWER(t.status) NOT IN ('done', 'canceled', 'duplicate')
        ORDER BY t.due_date ASC, t.id ASC
        </script>
        """)
List<com.linearlite.server.dto.DailySummaryTaskDto> selectDueForDigest(
        @Param("projectIds") List<Long> projectIds,
        @Param("endOfToday") java.time.LocalDateTime endOfToday);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd linear-lite-server && rtk proxy mvn -q -Dtest=DailySummaryQueryServiceTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/huangzhiwen/Documents/work/02code/product/linear-lite-1
rtk git add linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java \
  linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java
rtk git commit -m "feat(email): include progress in daily summary query"
```

### Task 2: Rebuild Digest Rendering for Status Translation, Progress, and Safer Links

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java`

**Interfaces:**
- Consumes: `DigestMailComposer.compose(Project project, String recipientName, LocalDate businessDate, List<DailySummaryTaskDto> tasks)`
- Consumes: `DailySummaryTaskDto#getStatus(): String`, `DailySummaryTaskDto#getProgressPercent(): Integer`, `DailySummaryTaskDto#getProjectId(): Long`, `DailySummaryTaskDto#getTaskKey(): String`
- Produces: `DigestMailContent` whose `htmlBody` and `textBody` contain translated statuses, progress text, and normalized links/fallbacks

- [ ] **Step 1: Write the failing composer tests for translated status, progress, and links**

```java
@Test
void composesTranslatedStatusProgressAndLinks() {
    Project project = new Project();
    project.setId(10L);
    project.setName("Engineering");
    project.setIdentifier("ENG");

    DailySummaryTaskDto task = new DailySummaryTaskDto();
    task.setTaskId(1L);
    task.setTaskKey("ENG-1");
    task.setTitle("修复登录");
    task.setStatus("in_progress");
    task.setPriority("high");
    task.setProjectId(10L);
    task.setProgressPercent(65);
    task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
    task.setOverdue(false);

    DigestMailContent content = composer.compose(project, "alice", LocalDate.of(2026, 7, 24), List.of(task));

    assertTrue(content.getHtmlBody().contains("进行中"));
    assertTrue(content.getHtmlBody().contains("进度 65%"));
    assertTrue(content.getHtmlBody().contains("href=\"https://app.example.com/\""));
    assertTrue(content.getHtmlBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
    assertTrue(content.getTextBody().contains("状态 进行中"));
    assertTrue(content.getTextBody().contains("进度 65%"));
}

@Test
void degradesSafelyWhenBaseUrlIsBlankAndProgressIsMissing() {
    DigestMailComposer blankBaseUrlComposer = new DigestMailComposer("");

    Project project = new Project();
    project.setId(10L);
    project.setName("Engineering");

    DailySummaryTaskDto task = new DailySummaryTaskDto();
    task.setTaskId(1L);
    task.setTaskKey("ENG-1");
    task.setTitle("修复登录");
    task.setStatus(null);
    task.setProjectId(10L);
    task.setProgressPercent(null);
    task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
    task.setOverdue(false);

    DigestMailContent content = blankBaseUrlComposer.compose(project, "alice", LocalDate.of(2026, 7, 24), List.of(task));

    assertTrue(content.getHtmlBody().contains("状态 未设置"));
    assertTrue(content.getHtmlBody().contains("进度 --"));
    assertTrue(content.getHtmlBody().contains("请联系管理员配置访问地址"));
    assertTrue(!content.getHtmlBody().contains("href=\"/\""));
    assertTrue(content.getTextBody().contains("打开 Linear Lite：请联系管理员配置访问地址"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd linear-lite-server && rtk proxy mvn -q -Dtest=DigestMailComposerTest test`
Expected: FAIL because current output still contains raw enums, omits progress text, and always emits a clickable CTA link.

- [ ] **Step 3: Write the minimal composer implementation**

```java
private static final Map<String, String> STATUS_LABELS = Map.of(
        "backlog", "待规划",
        "todo", "待处理",
        "in_progress", "进行中",
        "in_review", "待审核",
        "done", "已完成",
        "canceled", "已取消",
        "duplicate", "重复任务");

private String statusLabel(String status) {
    if (status == null || status.isBlank()) return "未设置";
    return STATUS_LABELS.getOrDefault(status.toLowerCase(), "未设置");
}

private String progressLabel(Integer progressPercent) {
    return progressPercent == null ? "--" : progressPercent + "%";
}

private String buildRootUrl() {
    return publicBaseUrl.isBlank() ? "" : publicBaseUrl + "/";
}

private String buildTaskUrl(DailySummaryTaskDto task) {
    if (publicBaseUrl.isBlank()) return "";
    return publicBaseUrl + "/projects/" + task.getProjectId() + "/tasks/" + urlEncode(task.getTaskKey());
}
```

```java
sb.append("<body style=\"margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#0f172a;\">");
sb.append("<tr><td align=\"center\"><table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 10px 30px rgba(15,23,42,0.06);\">");
sb.append("<tr><td style=\"background:#e0f2fe;padding:18px 32px;border-bottom:1px solid #bae6fd;\"><span style=\"font-size:18px;font-weight:700;color:#0369a1;\">Linear Lite</span></td></tr>");
```

```java
sb.append("<span style=\"display:block;font-size:12px;color:#64748b;margin-top:4px;\">截止 ")
  .append(formatDate(task.getDueDate()))
  .append(" · 状态 ")
  .append(escape(statusLabel(task.getStatus())))
  .append(" · 进度 ")
  .append(escape(progressLabel(task.getProgressPercent())))
  .append("</span>");
```

```java
String rootUrl = buildRootUrl();
if (!rootUrl.isBlank()) {
    sb.append("<a href=\"").append(rootUrl).append("\" style=\"display:inline-block;padding:10px 18px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:6px;font-size:14px;font-weight:600;\">打开 Linear Lite</a>");
} else {
    sb.append("<span style=\"display:inline-block;padding:10px 18px;background:#e2e8f0;color:#64748b;border-radius:6px;font-size:14px;font-weight:600;\">请联系管理员配置访问地址</span>");
}
```

```java
sb.append("打开 Linear Lite：")
  .append(rootUrl.isBlank() ? "请联系管理员配置访问地址" : rootUrl)
  .append("\n\n");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd linear-lite-server && rtk proxy mvn -q -Dtest=DigestMailComposerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/huangzhiwen/Documents/work/02code/product/linear-lite-1
rtk git add linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java
rtk git commit -m "feat(email): refresh digest template content"
```

### Task 3: Run Focused Verification Across the Touched Digest Flow

**Files:**
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryDispatchServiceTest.java`

**Interfaces:**
- Consumes: existing unit test entrypoints only
- Produces: evidence that query classification, composer output, and dispatch integration still pass after the refresh

- [ ] **Step 1: Run the focused verification set**

```bash
cd /Users/huangzhiwen/Documents/work/02code/product/linear-lite-1/linear-lite-server
rtk proxy mvn -q -Dtest=DailySummaryQueryServiceTest,DigestMailComposerTest,DailySummaryDispatchServiceTest test
```

Expected: PASS for all three test classes.

- [ ] **Step 2: Inspect for accidental diff spillover**

```bash
cd /Users/huangzhiwen/Documents/work/02code/product/linear-lite-1
rtk git diff -- linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java \
  linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java \
  linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java
```

Expected: Only the planned DTO, mapper, composer, and test updates appear.

- [ ] **Step 3: Commit the final verification checkpoint**

```bash
cd /Users/huangzhiwen/Documents/work/02code/product/linear-lite-1
rtk git add linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java \
  linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java \
  linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java \
  linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java
rtk git commit -m "test(email): verify daily summary refresh"
```

## Self-Review

- Spec coverage: Task 1 covers `progressPercent` data flow, Task 2 covers visual refresh, status translation, progress display, and link behavior, Task 3 covers focused verification across query/composer/dispatch boundaries.
- Placeholder scan: no `TODO`/`TBD` placeholders remain; commands, files, and test expectations are explicit.
- Type consistency: `progressPercent` is consistently defined as `Integer`, the composer continues to return `DigestMailContent`, and `selectDueForDigest(...)` remains the same public query entrypoint.
