# 项目邮件通知（今日汇总）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每天服务器时区 16:30 向启用「今日汇总」的项目中、有邮箱的任务负责人发送一封专业 HTML 汇总邮件，覆盖今日到期与已逾期未完成任务。

**Architecture:** 后端新增 `project_email_preferences`（项目场景开关）与 `project_email_dispatches`（发送记录）两张表；新增 `ProjectEmailPreferenceService` 维护开关并在建项目时初始化、`DailySummaryService` 查询与分组、`DigestMailComposer` 渲染 HTML/纯文本、`DigestMailSender` 发送 `MimeMessage`、`DailySummaryScheduler` 用 `@Scheduled` 触发；新增 `ProjectEmailPreferenceController` 暴露开关 API；前端 `projectApi` 增加邮箱设置方法，`ProjectSettingsModal`/`ProjectSettingsDialog` 增加邮件通知开关区域。

**Tech Stack:** Spring Boot 3.2.5、MyBatis-Plus 3.5.5、MySQL 8、JUnit 5 + Mockito、Vue 3 + Pinia + Vitest。

**设计依据：** `docs/superpowers/specs/2026-07-24-email-digest-design.md`

## Global Constraints

- 数据库不使用外键，表间关联用逻辑 `*_id`，一致性由应用层维护。
- 终态任务集合：`done`、`canceled`、`duplicate`。
- 场景键当前阶段只有 `daily_summary`。
- 项目开关默认 `enabled = false`。
- 发送记录唯一键：`(project_id, scenario_key, business_date, recipient_user_id)`。
- 邮件跳转链接用 `app.public-base-url` 拼绝对路径；任务链接格式 `/projects/{projectId}/tasks/{taskKey}`。
- 收件人按 `assigneeId` 归属；只发给 `users.email` 非空用户。
- 严禁字段兜底/多键回退；只读唯一字段。
- 提交信息用 Conventional Commit 中文描述。

---

### Task 1: 数据库表与实体

**Files:**
- Create: `linear-lite-server/src/main/resources/schema-v14-project-email.sql`
- Modify: `linear-lite-server/src/main/resources/schema.sql`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectEmailPreference.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectEmailDispatch.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectEmailPreferenceMapper.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectEmailDispatchMapper.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/entity/ProjectEmailPreferenceEntityTest.java`

**Interfaces:**
- Produces: `ProjectEmailPreference`（`projectId`、`scenarioKey`、`enabled`、`createdAt`、`updatedAt`）、`ProjectEmailDispatch`（`projectId`、`scenarioKey`、`businessDate`、`recipientUserId`、`status`、`subject`、`taskCount`、`lastError`、`sentAt`、`createdAt`、`updatedAt`）、`ProjectEmailPreferenceMapper extends BaseMapper<ProjectEmailPreference>`、`ProjectEmailDispatchMapper extends BaseMapper<ProjectEmailDispatch>`。

- [ ] **Step 1: 编写失败测试**

创建 `ProjectEmailPreferenceEntityTest.java`：

```java
package com.linearlite.server.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProjectEmailPreferenceEntityTest {

    @Test
    void preferenceHoldsProjectScenarioAndEnabledFlag() {
        ProjectEmailPreference preference = new ProjectEmailPreference();
        preference.setId(1L);
        preference.setProjectId(10L);
        preference.setScenarioKey("daily_summary");
        preference.setEnabled(false);
        preference.setCreatedAt(LocalDateTime.now());
        preference.setUpdatedAt(LocalDateTime.now());

        assertEquals(10L, preference.getProjectId());
        assertEquals("daily_summary", preference.getScenarioKey());
        assertEquals(false, preference.getEnabled());
        assertNotNull(preference.getCreatedAt());
        assertNotNull(preference.getUpdatedAt());
    }

    @Test
    void dispatchHoldsBusinessDateAndStatus() {
        ProjectEmailDispatch dispatch = new ProjectEmailDispatch();
        dispatch.setId(2L);
        dispatch.setProjectId(10L);
        dispatch.setScenarioKey("daily_summary");
        dispatch.setBusinessDate(LocalDate.of(2026, 7, 24));
        dispatch.setRecipientUserId(7L);
        dispatch.setStatus("pending");
        dispatch.setSubject("今日汇总");
        dispatch.setTaskCount(3);
        dispatch.setSentAt(null);
        dispatch.setCreatedAt(LocalDateTime.now());
        dispatch.setUpdatedAt(LocalDateTime.now());

        assertEquals(LocalDate.of(2026, 7, 24), dispatch.getBusinessDate());
        assertEquals("pending", dispatch.getStatus());
        assertEquals(3, dispatch.getTaskCount());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectEmailPreferenceEntityTest test`
Expected: FAIL，实体类不存在，编译失败。

- [ ] **Step 3: 实现实体与 Mapper**

`ProjectEmailPreference.java`：

```java
package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_email_preferences")
public class ProjectEmailPreference {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String scenarioKey;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getScenarioKey() { return scenarioKey; }
    public void setScenarioKey(String scenarioKey) { this.scenarioKey = scenarioKey; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

`ProjectEmailDispatch.java`：

```java
package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("project_email_dispatches")
public class ProjectEmailDispatch {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String scenarioKey;
    private LocalDate businessDate;
    private Long recipientUserId;
    private String status;
    private String subject;
    private Integer taskCount;
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getScenarioKey() { return scenarioKey; }
    public void setScenarioKey(String scenarioKey) { this.scenarioKey = scenarioKey; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public Long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(Long recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public Integer getTaskCount() { return taskCount; }
    public void setTaskCount(Integer taskCount) { this.taskCount = taskCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

`ProjectEmailPreferenceMapper.java`：

```java
package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectEmailPreference;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectEmailPreferenceMapper extends BaseMapper<ProjectEmailPreference> {
}
```

`ProjectEmailDispatchMapper.java`：

```java
package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectEmailDispatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectEmailDispatchMapper extends BaseMapper<ProjectEmailDispatch> {
}
```

- [ ] **Step 4: 编写 schema 增量与主 schema**

创建 `schema-v14-project-email.sql`：

```sql
-- 已有库增量：项目邮件偏好与发送记录。
-- 执行：mysql ... < schema-v14-project-email.sql
-- 新环境请直接执行 schema.sql，无需本脚本。

CREATE TABLE IF NOT EXISTS project_email_preferences (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    scenario_key  VARCHAR(32)  NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_email_preferences_project_scenario (project_id, scenario_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_email_preferences_project ON project_email_preferences (project_id);

CREATE TABLE IF NOT EXISTS project_email_dispatches (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
    scenario_key       VARCHAR(32)  NOT NULL,
    business_date      DATE         NOT NULL,
    recipient_user_id  BIGINT       NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    subject            VARCHAR(255) NOT NULL,
    task_count         INT          NOT NULL,
    last_error         VARCHAR(1024) DEFAULT NULL,
    sent_at            DATETIME     DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_email_dispatches_key (project_id, scenario_key, business_date, recipient_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_email_dispatches_project_date
ON project_email_dispatches (project_id, scenario_key, business_date);

-- 既有项目回填 daily_summary 默认关闭记录
INSERT INTO project_email_preferences (project_id, scenario_key, enabled)
SELECT p.id, 'daily_summary', 0
FROM projects p
WHERE NOT EXISTS (
    SELECT 1 FROM project_email_preferences pep
    WHERE pep.project_id = p.id AND pep.scenario_key = 'daily_summary'
);
```

在 `schema.sql` 文件末尾追加同样的 `CREATE TABLE IF NOT EXISTS` 两张表定义（不含回填 `INSERT`，因为全新库无既有项目）；索引与唯一键同上。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectEmailPreferenceEntityTest test`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add linear-lite-server/src/main/resources/schema-v14-project-email.sql linear-lite-server/src/main/resources/schema.sql linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectEmailPreference.java linear-lite-server/src/main/java/com/linearlite/server/entity/ProjectEmailDispatch.java linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectEmailPreferenceMapper.java linear-lite-server/src/main/java/com/linearlite/server/mapper/ProjectEmailDispatchMapper.java linear-lite-server/src/test/java/com/linearlite/server/entity/ProjectEmailPreferenceEntityTest.java
git commit -m "feat(email): 增加项目邮件偏好与发送记录表结构"
```

---

### Task 2: 项目邮件偏好服务与建项目初始化

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectEmailPreferenceService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java`（`create` 内调用初始化）
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/ProjectEmailPreferenceServiceTest.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/ProjectServiceTest.java`（新增用例）

**Interfaces:**
- Consumes: `ProjectEmailPreferenceMapper`
- Produces: `ProjectEmailPreferenceService.initializeForProject(Long projectId)`、`ProjectEmailPreferenceService.isEnabled(Long projectId, String scenarioKey)`、`ProjectEmailPreferenceService.setEnabled(Long projectId, String scenarioKey, boolean enabled)`、`ProjectEmailPreferenceService.listEnabledProjectIds(String scenarioKey)`

- [ ] **Step 1: 编写失败测试**

创建 `ProjectEmailPreferenceServiceTest.java`：

```java
package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectEmailPreference;
import com.linearlite.server.mapper.ProjectEmailPreferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectEmailPreferenceServiceTest {

    @Mock
    private ProjectEmailPreferenceMapper mapper;

    private ProjectEmailPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new ProjectEmailPreferenceService(mapper);
    }

    @Test
    void initializeForProjectInsertsDailySummaryDisabled() {
        service.initializeForProject(10L);

        ArgumentCaptor<ProjectEmailPreference> captor = ArgumentCaptor.forClass(ProjectEmailPreference.class);
        verify(mapper).insert(captor.capture());
        ProjectEmailPreference saved = captor.getValue();
        assertEquals(10L, saved.getProjectId());
        assertEquals("daily_summary", saved.getScenarioKey());
        assertEquals(false, saved.getEnabled());
    }

    @Test
    void isEnabledReturnsFalseWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        boolean result = service.isEnabled(10L, "daily_summary");

        assertFalse(result);
    }

    @Test
    void isEnabledReturnsStoredFlag() {
        ProjectEmailPreference preference = new ProjectEmailPreference();
        preference.setEnabled(true);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(preference);

        boolean result = service.isEnabled(10L, "daily_summary");

        assertTrue(result);
    }

    @Test
    void setEnabledUpdatesExistingRecord() {
        ProjectEmailPreference existing = new ProjectEmailPreference();
        existing.setId(5L);
        existing.setEnabled(false);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        service.setEnabled(10L, "daily_summary", true);

        assertEquals(true, existing.getEnabled());
        verify(mapper).updateById(existing);
    }

    @Test
    void listEnabledProjectIdsReturnsProjectIdsForEnabledScenario() {
        ProjectEmailPreference a = new ProjectEmailPreference();
        a.setProjectId(1L);
        a.setEnabled(true);
        ProjectEmailPreference b = new ProjectEmailPreference();
        b.setProjectId(2L);
        b.setEnabled(true);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));

        List<Long> ids = service.listEnabledProjectIds("daily_summary");

        assertEquals(List.of(1L, 2L), ids);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectEmailPreferenceServiceTest test`
Expected: FAIL，`ProjectEmailPreferenceService` 不存在。

- [ ] **Step 3: 实现 ProjectEmailPreferenceService**

```java
package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectEmailPreference;
import com.linearlite.server.mapper.ProjectEmailPreferenceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectEmailPreferenceService {

    public static final String SCENARIO_DAILY_SUMMARY = "daily_summary";

    private final ProjectEmailPreferenceMapper mapper;

    public ProjectEmailPreferenceService(ProjectEmailPreferenceMapper mapper) {
        this.mapper = mapper;
    }

    public void initializeForProject(Long projectId) {
        ProjectEmailPreference preference = new ProjectEmailPreference();
        preference.setProjectId(projectId);
        preference.setScenarioKey(SCENARIO_DAILY_SUMMARY);
        preference.setEnabled(false);
        preference.setCreatedAt(LocalDateTime.now());
        preference.setUpdatedAt(LocalDateTime.now());
        mapper.insert(preference);
    }

    public boolean isEnabled(Long projectId, String scenarioKey) {
        ProjectEmailPreference preference = select(projectId, scenarioKey);
        return preference != null && Boolean.TRUE.equals(preference.getEnabled());
    }

    public void setEnabled(Long projectId, String scenarioKey, boolean enabled) {
        ProjectEmailPreference preference = select(projectId, scenarioKey);
        if (preference == null) {
            preference = new ProjectEmailPreference();
            preference.setProjectId(projectId);
            preference.setScenarioKey(scenarioKey);
            preference.setEnabled(enabled);
            preference.setCreatedAt(LocalDateTime.now());
            preference.setUpdatedAt(LocalDateTime.now());
            mapper.insert(preference);
            return;
        }
        preference.setEnabled(enabled);
        preference.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(preference);
    }

    public List<Long> listEnabledProjectIds(String scenarioKey) {
        return mapper.selectList(
                new LambdaQueryWrapper<ProjectEmailPreference>()
                        .eq(ProjectEmailPreference::getScenarioKey, scenarioKey)
                        .eq(ProjectEmailPreference::getEnabled, true)
        ).stream().map(ProjectEmailPreference::getProjectId).toList();
    }

    private ProjectEmailPreference select(Long projectId, String scenarioKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<ProjectEmailPreference>()
                        .eq(ProjectEmailPreference::getProjectId, projectId)
                        .eq(ProjectEmailPreference::getScenarioKey, scenarioKey));
    }
}
```

- [ ] **Step 4: 在 ProjectService.create 中调用初始化**

在 `ProjectService` 构造函数注入 `ProjectEmailPreferenceService`，并在 `create` 方法 `addMember(project.getId(), creatorId, "owner");` 之后、`return projectMapper.selectById(project.getId());` 之前调用 `projectEmailPreferenceService.initializeForProject(project.getId());`。

修改 `ProjectService`：构造函数新增参数 `ProjectEmailPreferenceService projectEmailPreferenceService`，存为字段 `this.projectEmailPreferenceService = projectEmailPreferenceService;`。

在 `create` 方法末尾修改为：

```java
        addMember(project.getId(), creatorId, "owner");
        projectEmailPreferenceService.initializeForProject(project.getId());
        return projectMapper.selectById(project.getId());
```

- [ ] **Step 5: 在 ProjectServiceTest 中新增初始化验证**

在 `ProjectServiceTest` 中新增 mock 字段 `@Mock private ProjectEmailPreferenceService projectEmailPreferenceService;`，并在构造 `ProjectService` 时传入；新增用例：

```java
    @Test
    void createInitializesEmailPreferenceForProject() {
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(7L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(7L)).thenReturn(projectWithId(7L));

        projectService.create("Engineering", "ENG", 1L);

        verify(projectEmailPreferenceService).initializeForProject(7L);
    }
```

（按 `ProjectServiceTest` 现有辅助方法风格补 `projectWithId`，若已有等价工厂方法则复用。）

- [ ] **Step 6: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectEmailPreferenceServiceTest,ProjectServiceTest test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/ProjectEmailPreferenceService.java linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java linear-lite-server/src/test/java/com/linearlite/server/service/ProjectEmailPreferenceServiceTest.java linear-lite-server/src/test/java/com/linearlite/server/service/ProjectServiceTest.java
git commit -m "feat(email): 项目邮件偏好服务与建项目初始化"
```

---

### Task 3: 汇总邮件查询与 DTO

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/DailySummaryQueryService.java`
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java`（新增 `@Select` 方法）
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java`

**Interfaces:**
- Consumes: `TaskMapper.selectDueForDigest`
- Produces: `DailySummaryTaskDto`（`taskId`、`taskKey`、`title`、`status`、`priority`、`projectId`、`assigneeId`、`assigneeUsername`、`assigneeEmail`、`dueDate`、`overdue`）、`DailySummaryQueryService.findDueTasks(List<Long> projectIds, LocalDateTime startOfToday, LocalDateTime endOfToday)`

说明：收件人邮箱与用户名由 SQL `JOIN users` 单一路径获取，写入 DTO；调度服务不再二次查 `users` 表。`overdue` 由 `dueDate < startOfToday` 判定；查询上界为 `endOfToday`（今天 23:59:59），保证今天晚些时候到期的任务也被纳入。

- [ ] **Step 1: 编写失败测试**

创建 `DailySummaryQueryServiceTest.java`：

```java
package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailySummaryQueryServiceTest {

    @Mock
    private TaskMapper taskMapper;

    private DailySummaryQueryService service;

    @BeforeEach
    void setUp() {
        service = new DailySummaryQueryService(taskMapper);
    }

    @Test
    void findDueTasksClassifiesTodayAndOverdue() {
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

        DailySummaryTaskDto overdue = new DailySummaryTaskDto();
        overdue.setTaskId(2L);
        overdue.setTaskKey("ENG-2");
        overdue.setTitle("已逾期");
        overdue.setProjectId(10L);
        overdue.setAssigneeId(7L);
        overdue.setAssigneeEmail("a@example.com");
        overdue.setDueDate(LocalDateTime.of(2026, 7, 20, 12, 0));

        when(taskMapper.selectDueForDigest(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(today, overdue));

        List<DailySummaryTaskDto> result = service.findDueTasks(List.of(10L), startOfToday, endOfToday);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(1L) && !t.getOverdue()));
        assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(2L) && t.getOverdue()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=DailySummaryQueryServiceTest test`
Expected: FAIL，类与方法不存在。

- [ ] **Step 3: 实现 DTO**

`DailySummaryTaskDto.java`：

```java
package com.linearlite.server.dto;

import java.time.LocalDateTime;

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
    private LocalDateTime dueDate;
    private Boolean overdue;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskKey() { return taskKey; }
    public void setTaskKey(String taskKey) { this.taskKey = taskKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public String getAssigneeUsername() { return assigneeUsername; }
    public void setAssigneeUsername(String assigneeUsername) { this.assigneeUsername = assigneeUsername; }
    public String getAssigneeEmail() { return assigneeEmail; }
    public void setAssigneeEmail(String assigneeEmail) { this.assigneeEmail = assigneeEmail; }
    public LocalDateTime getDueDate() { return dueDate; }
    public void setDueDate(LocalDateTime dueDate) { this.dueDate = dueDate; }
    public Boolean getOverdue() { return overdue; }
    public void setOverdue(Boolean overdue) { this.overdue = overdue; }
}
```

- [ ] **Step 4: 在 TaskMapper 新增查询方法**

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

- [ ] **Step 5: 实现 DailySummaryQueryService**

```java
package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DailySummaryQueryService {

    private final TaskMapper taskMapper;

    public DailySummaryQueryService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public List<DailySummaryTaskDto> findDueTasks(List<Long> projectIds, LocalDateTime startOfToday, LocalDateTime endOfToday) {
        List<DailySummaryTaskDto> tasks = taskMapper.selectDueForDigest(projectIds, endOfToday);
        for (DailySummaryTaskDto task : tasks) {
            task.setOverdue(task.getDueDate() != null && task.getDueDate().isBefore(startOfToday));
        }
        return tasks;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=DailySummaryQueryServiceTest test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/dto/DailySummaryTaskDto.java linear-lite-server/src/main/java/com/linearlite/server/service/DailySummaryQueryService.java linear-lite-server/src/main/java/com/linearlite/server/mapper/TaskMapper.java linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryQueryServiceTest.java
git commit -m "feat(email): 今日汇总任务查询与逾期分类"
```

---

### Task 4: 邮件内容编排器（HTML + 纯文本）

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/DigestMailContent.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java`

**Interfaces:**
- Consumes: `DailySummaryTaskDto`、`Project.name`、`app.public-base-url`
- Produces: `DigestMailContent`（`subject`、`htmlBody`、`textBody`、`taskCount`）

- [ ] **Step 1: 编写失败测试**

创建 `DigestMailComposerTest.java`：

```java
package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestMailComposerTest {

    private DigestMailComposer composer;

    @BeforeEach
    void setUp() {
        composer = new DigestMailComposer("https://app.example.com");
    }

    @Test
    void composesSubjectAndBodiesWithTaskLink() {
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
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = composer.compose(project, "alice", List.of(task));

        assertTrue(content.getSubject().contains("Engineering"));
        assertTrue(content.getHtmlBody().contains("修复登录"));
        assertTrue(content.getHtmlBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
        assertTrue(content.getTextBody().contains("ENG-1"));
        assertTrue(content.getTextBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
        assertEquals(1, content.getTaskCount());
    }

    @Test
    void htmlEscapesTaskTitle() {
        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");
        project.setIdentifier("ENG");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("<script>alert(1)</script>");
        task.setProjectId(10L);
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = composer.compose(project, "alice", List.of(task));

        assertTrue(content.getHtmlBody().contains("&lt;script&gt;"));
        assertTrue(!content.getHtmlBody().contains("<script>alert"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=DigestMailComposerTest test`
Expected: FAIL，类不存在。

- [ ] **Step 3: 实现 DTO 与 Composer**

`DigestMailContent.java`：

```java
package com.linearlite.server.dto;

public class DigestMailContent {
    private final String subject;
    private final String htmlBody;
    private final String textBody;
    private final int taskCount;

    public DigestMailContent(String subject, String htmlBody, String textBody, int taskCount) {
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.textBody = textBody;
        this.taskCount = taskCount;
    }

    public String getSubject() { return subject; }
    public String getHtmlBody() { return htmlBody; }
    public String getTextBody() { return textBody; }
    public int getTaskCount() { return taskCount; }
}
```

`DigestMailComposer.java`：

```java
package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DigestMailComposer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String publicBaseUrl;

    public DigestMailComposer(@Value("${app.public-base-url:}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public DigestMailContent compose(Project project, String recipientName, List<DailySummaryTaskDto> tasks) {
        String today = LocalDateTime.now().format(DATE_FMT);
        String subject = "【今日汇总】" + project.getName() + " · " + today;

        List<DailySummaryTaskDto> overdue = tasks.stream().filter(t -> Boolean.TRUE.equals(t.getOverdue())).toList();
        List<DailySummaryTaskDto> dueToday = tasks.stream().filter(t -> !Boolean.TRUE.equals(t.getOverdue())).toList();

        String html = buildHtml(project, recipientName, today, dueToday, overdue);
        String text = buildText(project, recipientName, today, dueToday, overdue);

        return new DigestMailContent(subject, html, text, tasks.size());
    }

    private String buildHtml(Project project, String recipientName, String today,
                             List<DailySummaryTaskDto> dueToday, List<DailySummaryTaskDto> overdue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0;padding:0;background:#f6f7f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#1f2937;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f6f7f9;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\"><table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.08);\">");
        sb.append("<tr><td style=\"background:#5e6ad2;padding:20px 32px;\"><span style=\"font-size:18px;font-weight:600;color:#ffffff;letter-spacing:0.4px;\">Linear Lite</span></td></tr>");
        sb.append("<tr><td style=\"padding:28px 32px 8px 32px;\">");
        sb.append("<p style=\"margin:0 0 4px 0;font-size:15px;color:#6b7280;\">").append(escape("Hi " + nullSafe(recipientName))).append("</p>");
        sb.append("<h1 style=\"margin:0 0 12px 0;font-size:22px;font-weight:600;color:#111827;\">").append(escape(project.getName())).append(" · 今日汇总</h1>");
        sb.append("<p style=\"margin:0;font-size:13px;color:#9ca3af;\">").append(today).append(" · 共 ").append(dueToday.size() + overdue.size()).append(" 个待处理任务</p>");
        sb.append("</td></tr>");

        if (!overdue.isEmpty()) {
            sb.append("<tr><td style=\"padding:8px 32px 0 32px;\"><h2 style=\"margin:0 0 8px 0;font-size:14px;color:#dc2626;\">已逾期 · ").append(overdue.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(overdue));
            sb.append("</td></tr>");
        }
        if (!dueToday.isEmpty()) {
            sb.append("<tr><td style=\"padding:16px 32px 0 32px;\"><h2 style=\"margin:0 0 8px 0;font-size:14px;color:#111827;\">今日到期 · ").append(dueToday.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(dueToday));
            sb.append("</td></tr>");
        }

        sb.append("<tr><td style=\"padding:24px 32px;\"><a href=\"").append(publicBaseUrl).append("/\" style=\"display:inline-block;padding:10px 20px;background:#5e6ad2;color:#ffffff;text-decoration:none;border-radius:6px;font-size:14px;font-weight:500;\">打开 Linear Lite</a></td></tr>");
        sb.append("<tr><td style=\"padding:16px 32px 28px 32px;border-top:1px solid #f0f0f0;\"><p style=\"margin:0;font-size:12px;color:#9ca3af;\">这封邮件由 Linear Lite 自动发送。如需关闭，请在项目设置中关闭「今日汇总」。</p></td></tr>");
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private String buildTaskRowsHtml(List<DailySummaryTaskDto> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">");
        for (DailySummaryTaskDto task : tasks) {
            String url = publicBaseUrl + "/projects/" + task.getProjectId() + "/tasks/" + urlEncode(task.getTaskKey());
            sb.append("<tr><td style=\"padding:10px 0;border-bottom:1px solid #f3f4f6;\">");
            sb.append("<a href=\"").append(url).append("\" style=\"font-size:14px;font-weight:500;color:#5e6ad2;text-decoration:none;\">").append(escape(task.getTaskKey())).append("</a>");
            sb.append("<span style=\"font-size:14px;color:#374151;margin-left:8px;\">").append(escape(task.getTitle())).append("</span>");
            sb.append("<span style=\"display:block;font-size:12px;color:#9ca3af;margin-top:2px;\">截止 ").append(formatDate(task.getDueDate())).append(" · 状态 ").append(escape(nullSafe(task.getStatus()))).append("</span>");
            sb.append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildText(Project project, String recipientName, String today,
                             List<DailySummaryTaskDto> dueToday, List<DailySummaryTaskDto> overdue) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(nullSafe(recipientName)).append("\n\n");
        sb.append(project.getName()).append(" · 今日汇总\n").append(today).append(" · 共 ")
          .append(dueToday.size() + overdue.size()).append(" 个待处理任务\n\n");
        if (!overdue.isEmpty()) {
            sb.append("已逾期 · ").append(overdue.size()).append("\n");
            for (DailySummaryTaskDto task : overdue) {
                sb.append("- [").append(task.getTaskKey()).append("] ").append(task.getTitle())
                  .append("（截止 ").append(formatDate(task.getDueDate())).append("）\n")
                  .append(publicBaseUrl).append("/projects/").append(task.getProjectId())
                  .append("/tasks/").append(task.getTaskKey()).append("\n");
            }
            sb.append("\n");
        }
        if (!dueToday.isEmpty()) {
            sb.append("今日到期 · ").append(dueToday.size()).append("\n");
            for (DailySummaryTaskDto task : dueToday) {
                sb.append("- [").append(task.getTaskKey()).append("] ").append(task.getTitle())
                  .append("（截止 ").append(formatDate(task.getDueDate())).append("）\n")
                  .append(publicBaseUrl).append("/projects/").append(task.getProjectId())
                  .append("/tasks/").append(task.getTaskKey()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("打开 Linear Lite：").append(publicBaseUrl).append("/\n\n");
        sb.append("这封邮件由 Linear Lite 自动发送。如需关闭，请在项目设置中关闭「今日汇总」。\n");
        return sb.toString();
    }

    private String formatDate(LocalDateTime date) {
        return date == null ? "—" : date.format(DATETIME_FMT);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=DigestMailComposerTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailComposer.java linear-lite-server/src/main/java/com/linearlite/server/dto/DigestMailContent.java linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailComposerTest.java
git commit -m "feat(email): 今日汇总邮件 HTML 与纯文本编排"
```

---

### Task 5: 邮件发送器（MimeMessage）

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailSender.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailSenderTest.java`

**Interfaces:**
- Consumes: `JavaMailSender`、`app.mail.from`、`app.mail.from-name`
- Produces: `DigestMailSender.send(String to, DigestMailContent content)`

- [ ] **Step 1: 编写失败测试**

创建 `DigestMailSenderTest.java`：

```java
package com.linearlite.server.service;

import com.linearlite.server.dto.DigestMailContent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigestMailSenderTest {

    private JavaMailSender mailSender;
    private DigestMailSender sender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        sender = new DigestMailSender(mailSender, "noreply@example.com", "Linear Lite");
    }

    @Test
    void sendInvokesMailSenderWithMimeMessage() {
        DigestMailContent content = new DigestMailContent("主题", "<html></html>", "text", 1);

        sender.send("user@example.com", content);

        verify(mailSender).send(any(MimeMessage.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=DigestMailSenderTest test`
Expected: FAIL，类不存在。

- [ ] **Step 3: 实现 DigestMailSender**

```java
package com.linearlite.server.service;

import com.linearlite.server.dto.DigestMailContent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class DigestMailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public DigestMailSender(
            JavaMailSender mailSender,
            @Value("${app.mail.from:}") String fromAddress,
            @Value("${app.mail.from-name:Linear Lite}") String fromName
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public void send(String to, DigestMailContent content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            }
            helper.setTo(to);
            helper.setSubject(content.getSubject());
            helper.setText(content.getTextBody(), content.getHtmlBody());
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("发送汇总邮件失败: " + to, e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=DigestMailSenderTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/DigestMailSender.java linear-lite-server/src/test/java/com/linearlite/server/service/DigestMailSenderTest.java
git commit -m "feat(email): 汇总邮件 MimeMessage 发送器"
```

---

### Task 6: 汇总发送编排服务与幂等记录

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/DailySummaryDispatchService.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryDispatchServiceTest.java`

**Interfaces:**
- Consumes: `ProjectEmailPreferenceService.listEnabledProjectIds`、`DailySummaryQueryService.findDueTasks`、`ProjectMapper`、`DigestMailComposer`、`DigestMailSender`、`ProjectEmailDispatchMapper`
- Produces: `DailySummaryDispatchService.dispatchForDate(LocalDate businessDate)`

说明：收件人 `email`、`username` 直接取自 `DailySummaryTaskDto`（由查询 SQL `JOIN users` 单一路径填充），服务内部不再查 `users` 表。`startOfToday`、`endOfToday` 由 `businessDate` 推导，调度器只传 `businessDate`。

- [ ] **Step 1: 编写失败测试**

创建 `DailySummaryDispatchServiceTest.java`：

```java
package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectEmailDispatch;
import com.linearlite.server.mapper.ProjectEmailDispatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailySummaryDispatchServiceTest {

    @Mock private ProjectEmailPreferenceService preferenceService;
    @Mock private DailySummaryQueryService queryService;
    @Mock private ProjectMapper projectMapper;
    @Mock private DigestMailComposer composer;
    @Mock private DigestMailSender sender;
    @Mock private ProjectEmailDispatchMapper dispatchMapper;

    private DailySummaryDispatchService service;

    @BeforeEach
    void setUp() {
        service = new DailySummaryDispatchService(
                preferenceService, queryService, projectMapper,
                composer, sender, dispatchMapper);
    }

    @Test
    void skipsProjectWithoutDueTasks() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of());

        service.dispatchForDate(date);

        verify(dispatchMapper, never()).insert(any(ProjectEmailDispatch.class));
        verify(sender, never()).send(anyString(), any());
    }

    @Test
    void sendsAndRecordsPerRecipient() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("修复");
        task.setProjectId(10L);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail("a@example.com");

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of(task));
        when(composer.compose(eq(project), eq("alice"), any())).thenReturn(
                new DigestMailContent("主题", "<html/>", "text", 1));
        when(dispatchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.dispatchForDate(date);

        verify(sender).send(eq("a@example.com"), any(DigestMailContent.class));
        ArgumentCaptor<ProjectEmailDispatch> captor = ArgumentCaptor.forClass(ProjectEmailDispatch.class);
        verify(dispatchMapper, times(1)).insert(captor.capture());
        verify(dispatchMapper, times(1)).updateById(any(ProjectEmailDispatch.class));
        ProjectEmailDispatch recorded = captor.getValue();
        assertEquals("daily_summary", recorded.getScenarioKey());
        assertEquals("sent", recorded.getStatus());
    }

    @Test
    void skipsRecipientWithoutEmail() {
        LocalDate date = LocalDate.of(2026, 7, 24);

        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setProjectId(10L);
        task.setAssigneeId(7L);
        task.setAssigneeUsername("alice");
        task.setAssigneeEmail(null);

        when(preferenceService.listEnabledProjectIds("daily_summary")).thenReturn(List.of(10L));
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(queryService.findDueTasks(eq(List.of(10L)), any(), any())).thenReturn(List.of(task));

        service.dispatchForDate(date);

        verify(sender, never()).send(anyString(), any());
        verify(dispatchMapper, never()).insert(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=DailySummaryDispatchServiceTest test`
Expected: FAIL，类不存在。

- [ ] **Step 3: 实现 DailySummaryDispatchService**

```java
package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectEmailDispatch;
import com.linearlite.server.mapper.ProjectEmailDispatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DailySummaryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryDispatchService.class);

    private final ProjectEmailPreferenceService preferenceService;
    private final DailySummaryQueryService queryService;
    private final ProjectMapper projectMapper;
    private final DigestMailComposer composer;
    private final DigestMailSender sender;
    private final ProjectEmailDispatchMapper dispatchMapper;

    public DailySummaryDispatchService(
            ProjectEmailPreferenceService preferenceService,
            DailySummaryQueryService queryService,
            ProjectMapper projectMapper,
            DigestMailComposer composer,
            DigestMailSender sender,
            ProjectEmailDispatchMapper dispatchMapper) {
        this.preferenceService = preferenceService;
        this.queryService = queryService;
        this.projectMapper = projectMapper;
        this.composer = composer;
        this.sender = sender;
        this.dispatchMapper = dispatchMapper;
    }

    public void dispatchForDate(LocalDate businessDate) {
        List<Long> projectIds = preferenceService.listEnabledProjectIds(
                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);
        if (projectIds.isEmpty()) return;

        LocalDateTime startOfToday = businessDate.atStartOfDay();
        LocalDateTime endOfToday = businessDate.plusDays(1).atStartOfDay();
        List<DailySummaryTaskDto> tasks = queryService.findDueTasks(projectIds, startOfToday, endOfToday);
        if (tasks.isEmpty()) return;

        Map<Long, List<DailySummaryTaskDto>> tasksByProject = tasks.stream()
                .collect(Collectors.groupingBy(DailySummaryTaskDto::getProjectId));

        for (Long projectId : projectIds) {
            List<DailySummaryTaskDto> projectTasks = tasksByProject.get(projectId);
            if (projectTasks == null || projectTasks.isEmpty()) continue;

            Project project = projectMapper.selectById(projectId);
            if (project == null) continue;

            dispatchForProject(project, projectTasks, businessDate);
        }
    }

    private void dispatchForProject(Project project, List<DailySummaryTaskDto> tasks, LocalDate businessDate) {
        Map<Long, List<DailySummaryTaskDto>> tasksByAssignee = tasks.stream()
                .filter(t -> t.getAssigneeId() != null)
                .collect(Collectors.groupingBy(DailySummaryTaskDto::getAssigneeId));

        for (Map.Entry<Long, List<DailySummaryTaskDto>> entry : tasksByAssignee.entrySet()) {
            DailySummaryTaskDto first = entry.getValue().get(0);
            String email = first.getAssigneeEmail();
            String username = first.getAssigneeUsername();
            if (email == null || email.isBlank()) continue;

            dispatchOne(project, entry.getKey(), username, email, entry.getValue(), businessDate);
        }
    }

    private void dispatchOne(Project project, Long recipientUserId, String recipientName,
                             String recipientEmail, List<DailySummaryTaskDto> tasks, LocalDate businessDate) {
        ProjectEmailDispatch existing = dispatchMapper.selectOne(
                new LambdaQueryWrapper<ProjectEmailDispatch>()
                        .eq(ProjectEmailDispatch::getProjectId, project.getId())
                        .eq(ProjectEmailDispatch::getScenarioKey,
                                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY)
                        .eq(ProjectEmailDispatch::getBusinessDate, businessDate)
                        .eq(ProjectEmailDispatch::getRecipientUserId, recipientUserId));
        if (existing != null && "sent".equals(existing.getStatus())) return;

        DigestMailContent content = composer.compose(project, recipientName, tasks);

        ProjectEmailDispatch record;
        if (existing == null) {
            record = new ProjectEmailDispatch();
            record.setProjectId(project.getId());
            record.setScenarioKey(ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);
            record.setBusinessDate(businessDate);
            record.setRecipientUserId(recipientUserId);
            record.setStatus("pending");
            record.setSubject(content.getSubject());
            record.setTaskCount(content.getTaskCount());
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            dispatchMapper.insert(record);
        } else {
            record = existing;
            record.setSubject(content.getSubject());
            record.setTaskCount(content.getTaskCount());
            record.setLastError(null);
        }

        try {
            sender.send(recipientEmail, content);
            record.setStatus("sent");
            record.setSentAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            dispatchMapper.updateById(record);
        } catch (RuntimeException e) {
            record.setStatus("failed");
            String msg = e.getMessage();
            record.setLastError(msg == null ? null : msg.substring(0, Math.min(msg.length(), 1024)));
            record.setUpdatedAt(LocalDateTime.now());
            dispatchMapper.updateById(record);
            log.warn("今日汇总邮件发送失败 projectId={} userId={}", project.getId(), recipientUserId, e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=DailySummaryDispatchServiceTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/service/DailySummaryDispatchService.java linear-lite-server/src/test/java/com/linearlite/server/service/DailySummaryDispatchServiceTest.java
git commit -m "feat(email): 今日汇总发送编排与幂等记录"
```

---

### Task 7: 调度器与启用调度

**Files:**
- Modify: `linear-lite-server/src/main/java/com/linearlite/server/LinearLiteServerApplication.java`（`@EnableScheduling`）
- Create: `linear-lite-server/src/main/java/com/linearlite/server/service/DailySummaryScheduler.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/service/DailySummarySchedulerTest.java`

**Interfaces:**
- Consumes: `DailySummaryDispatchService`
- Produces: `DailySummaryScheduler.runDaily()`

- [ ] **Step 1: 编写失败测试**

创建 `DailySummarySchedulerTest.java`：

```java
package com.linearlite.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DailySummarySchedulerTest {

    private DailySummaryDispatchService dispatchService;
    private DailySummaryScheduler scheduler;

    @BeforeEach
    void setUp() {
        dispatchService = mock(DailySummaryDispatchService.class);
        scheduler = new DailySummaryScheduler(dispatchService, ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void runDailyDispatchesForToday() {
        scheduler.runDaily();

        verify(dispatchService).dispatchForDate(any(LocalDate.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=DailySummarySchedulerTest test`
Expected: FAIL，类不存在。

- [ ] **Step 3: 启用调度并实现 Scheduler**

在 `LinearLiteServerApplication` 类注解新增 `@org.springframework.scheduling.annotation.EnableScheduling`。

`DailySummaryScheduler.java`：

```java
package com.linearlite.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class DailySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryScheduler.class);

    private final DailySummaryDispatchService dispatchService;
    private final ZoneId zoneId;

    public DailySummaryScheduler(
            DailySummaryDispatchService dispatchService,
            @Value("${app.email.digest.zone:Asia/Shanghai}") String zone) {
        this.dispatchService = dispatchService;
        this.zoneId = ZoneId.of(zone);
    }

    @Scheduled(cron = "${app.email.digest.cron:0 30 16 * * *}")
    public void runDaily() {
        LocalDate businessDate = LocalDate.now(zoneId);
        log.info("今日汇总调度开始 businessDate={}", businessDate);
        dispatchService.dispatchForDate(businessDate);
        log.info("今日汇总调度结束 businessDate={}", businessDate);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=DailySummarySchedulerTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/LinearLiteServerApplication.java linear-lite-server/src/main/java/com/linearlite/server/service/DailySummaryScheduler.java linear-lite-server/src/test/java/com/linearlite/server/service/DailySummarySchedulerTest.java
git commit -m "feat(email): 启用调度并每日 16:30 触发今日汇总"
```

---

### Task 8: 项目邮件配置 API

**Files:**
- Create: `linear-lite-server/src/main/java/com/linearlite/server/controller/ProjectEmailPreferenceController.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/EmailSettingsResponse.java`
- Create: `linear-lite-server/src/main/java/com/linearlite/server/dto/UpdateEmailSettingsRequest.java`
- Test: `linear-lite-server/src/test/java/com/linearlite/server/controller/ProjectEmailPreferenceControllerTest.java`

**Interfaces:**
- Consumes: `ProjectEmailPreferenceService`、`ProjectService`（成员校验与创建者校验）
- Produces: `GET /api/projects/{id}/email-settings`、`PUT /api/projects/{id}/email-settings`

- [ ] **Step 1: 编写失败测试**

创建 `ProjectEmailPreferenceControllerTest.java`：

```java
package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.EmailSettingsResponse;
import com.linearlite.server.dto.UpdateEmailSettingsRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectEmailPreferenceService;
import com.linearlite.server.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectEmailPreferenceControllerTest {

    private ProjectEmailPreferenceService preferenceService;
    private ProjectService projectService;
    private ProjectEmailPreferenceController controller;

    @BeforeEach
    void setUp() {
        preferenceService = mock(ProjectEmailPreferenceService.class);
        projectService = mock(ProjectService.class);
        controller = new ProjectEmailPreferenceController(preferenceService, projectService);
    }

    @Test
    void getEmailSettingsReturnsDailySummaryFlag() {
        when(preferenceService.isEnabled(10L, "daily_summary")).thenReturn(false);

        ResponseEntity<ApiResponse<List<EmailSettingsResponse>>> response = controller.list(10L);

        assertEquals(false, response.getBody().getData().get(0).getEnabled());
        assertEquals("daily_summary", response.getBody().getData().get(0).getScenarioKey());
    }

    @Test
    void putEmailSettingsUpdatesDailySummary() {
        Project project = new Project();
        project.setId(10L);
        project.setCreatorId(7L);
        when(projectService.requireProjectMember(10L, 7L)).thenReturn(project);

        UpdateEmailSettingsRequest.Item item = new UpdateEmailSettingsRequest.Item();
        item.setScenarioKey("daily_summary");
        item.setEnabled(true);
        UpdateEmailSettingsRequest request = new UpdateEmailSettingsRequest();
        request.setItems(List.of(item));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);

        controller.update(10L, request, httpRequest);

        verify(preferenceService).setEnabled(10L, "daily_summary", true);
    }

    @Test
    void putEmailSettingsRejectsNonCreator() {
        Project project = new Project();
        project.setId(10L);
        project.setCreatorId(99L);
        when(projectService.requireProjectMember(10L, 7L)).thenReturn(project);

        UpdateEmailSettingsRequest.Item item = new UpdateEmailSettingsRequest.Item();
        item.setScenarioKey("daily_summary");
        item.setEnabled(true);
        UpdateEmailSettingsRequest request = new UpdateEmailSettingsRequest();
        request.setItems(List.of(item));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);

        assertThrows(ForbiddenOperationException.class, () -> controller.update(10L, request, httpRequest));
    }
}
```

注：此测试依赖 `ProjectService.requireProjectMember` 返回 `Project`。若现有签名是 `void`，改为返回 `void` 并在 controller 内单独 `selectById` 取项目校验创建者——以实际签名为准，`requireProjectMember` 当前返回 `void`，故 controller 改为：调用 `projectService.requireProjectMember(projectId, userId)` 后用 `projectMapper` 不合适，改为让 `ProjectService` 暴露 `Project getByIdOrThrow(Long id)` 或直接在 controller 注入 `ProjectMapper`。**实现约定**：在 `ProjectService` 新增 `public Project loadProject(Long projectId)` 方法返回 `projectMapper.selectById(projectId)`，controller 先 `requireProjectMember` 再 `loadProject`。相应修正测试中 `when(projectService.requireProjectMember(...))` 为 void mock，并新增 `when(projectService.loadProject(10L)).thenReturn(project)`。

按此修正测试的 `putEmailSettings...` 两例：

```java
    @Test
    void putEmailSettingsUpdatesDailySummary() {
        Project project = new Project();
        project.setId(10L);
        project.setCreatorId(7L);
        when(projectService.loadProject(10L)).thenReturn(project);

        UpdateEmailSettingsRequest.Item item = new UpdateEmailSettingsRequest.Item();
        item.setScenarioKey("daily_summary");
        item.setEnabled(true);
        UpdateEmailSettingsRequest request = new UpdateEmailSettingsRequest();
        request.setItems(List.of(item));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);

        controller.update(10L, request, httpRequest);

        verify(preferenceService).setEnabled(10L, "daily_summary", true);
    }

    @Test
    void putEmailSettingsRejectsNonCreator() {
        Project project = new Project();
        project.setId(10L);
        project.setCreatorId(99L);
        when(projectService.loadProject(10L)).thenReturn(project);

        UpdateEmailSettingsRequest.Item item = new UpdateEmailSettingsRequest.Item();
        item.setScenarioKey("daily_summary");
        item.setEnabled(true);
        UpdateEmailSettingsRequest request = new UpdateEmailSettingsRequest();
        request.setItems(List.of(item));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);

        assertThrows(ForbiddenOperationException.class, () -> controller.update(10L, request, httpRequest));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectEmailPreferenceControllerTest test`
Expected: FAIL，类不存在。

- [ ] **Step 3: 实现 DTO 与 Controller**

`EmailSettingsResponse.java`：

```java
package com.linearlite.server.dto;

public class EmailSettingsResponse {
    private String scenarioKey;
    private Boolean enabled;

    public EmailSettingsResponse(String scenarioKey, Boolean enabled) {
        this.scenarioKey = scenarioKey;
        this.enabled = enabled;
    }

    public String getScenarioKey() { return scenarioKey; }
    public Boolean getEnabled() { return enabled; }
}
```

`UpdateEmailSettingsRequest.java`：

```java
package com.linearlite.server.dto;

import java.util.List;

public class UpdateEmailSettingsRequest {
    private List<Item> items;

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public static class Item {
        private String scenarioKey;
        private Boolean enabled;

        public String getScenarioKey() { return scenarioKey; }
        public void setScenarioKey(String scenarioKey) { this.scenarioKey = scenarioKey; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
```

在 `ProjectService` 新增方法：

```java
    public Project loadProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + projectId);
        }
        return project;
    }
```

`ProjectEmailPreferenceController.java`：

```java
package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.EmailSettingsResponse;
import com.linearlite.server.dto.UpdateEmailSettingsRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectEmailPreferenceService;
import com.linearlite.server.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{id}/email-settings")
public class ProjectEmailPreferenceController {

    private static final List<String> SUPPORTED_SCENARIOS = List.of(
            ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);

    private final ProjectEmailPreferenceService preferenceService;
    private final ProjectService projectService;

    public ProjectEmailPreferenceController(
            ProjectEmailPreferenceService preferenceService,
            ProjectService projectService) {
        this.preferenceService = preferenceService;
        this.projectService = projectService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmailSettingsResponse>>> list(@PathVariable("id") Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(List.of(
                new EmailSettingsResponse(
                        ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY,
                        preferenceService.isEnabled(projectId,
                                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY)))));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable("id") Long projectId,
            @RequestBody UpdateEmailSettingsRequest request,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        projectService.requireProjectMember(projectId, userId);
        Project project = projectService.loadProject(projectId);
        if (!userId.equals(project.getCreatorId())) {
            throw new ForbiddenOperationException("只有项目创建者可以修改邮件设置");
        }
        if (request.getItems() == null) {
            throw new IllegalArgumentException("缺少邮件设置项");
        }
        for (UpdateEmailSettingsRequest.Item item : request.getItems()) {
            if (item.getScenarioKey() == null || !SUPPORTED_SCENARIOS.contains(item.getScenarioKey())) {
                throw new IllegalArgumentException("不支持的场景: " + item.getScenarioKey());
            }
            preferenceService.setEnabled(projectId, item.getScenarioKey(), Boolean.TRUE.equals(item.getEnabled()));
        }
        return ResponseEntity.ok(ApiResponse.success());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd linear-lite-server && mvn -q -Dtest=ProjectEmailPreferenceControllerTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add linear-lite-server/src/main/java/com/linearlite/server/controller/ProjectEmailPreferenceController.java linear-lite-server/src/main/java/com/linearlite/server/dto/EmailSettingsResponse.java linear-lite-server/src/main/java/com/linearlite/server/dto/UpdateEmailSettingsRequest.java linear-lite-server/src/main/java/com/linearlite/server/service/ProjectService.java linear-lite-server/src/test/java/com/linearlite/server/controller/ProjectEmailPreferenceControllerTest.java
git commit -m "feat(email): 项目邮件配置查询与更新 API"
```

---

### Task 9: 配置项与全量后端编译

**Files:**
- Modify: `linear-lite-server/src/main/resources/application.yml`

- [ ] **Step 1: 新增配置占位**

在 `application.yml` 的 `app:` 节点下新增：

```yaml
  email:
    digest:
      cron: ${EMAIL_DIGEST_CRON:0 30 16 * * *}
      zone: ${EMAIL_DIGEST_ZONE:Asia/Shanghai}
  public-base-url: ${APP_PUBLIC_BASE_URL:}
  mail:
    from: ${MAIL_FROM:${MAIL_USERNAME:}}
    from-name: ${MAIL_FROM_NAME:Linear Lite}
```

（`app.mail.from` 已存在，仅补 `from-name`；`public-base-url` 与 `email.digest` 为新增。注意 YAML 缩进与现有 `app.mail`、`app.storage` 同级。）

- [ ] **Step 2: 全量编译与测试**

Run: `cd linear-lite-server && mvn -q test`
Expected: BUILD SUCCESS，所有后端测试通过。

- [ ] **Step 3: 提交**

```bash
git add linear-lite-server/src/main/resources/application.yml
git commit -m "chore(email): 新增汇总调度与发件人配置项"
```

---

### Task 10: 前端 API 层

**Files:**
- Modify: `src/services/api/project.ts`
- Modify: `src/store/projectStore.test.ts`（mock 补齐新方法，避免现有用例因 mock 缺方法报错）
- Create: `src/services/api/projectEmailSettings.test.ts`

**Interfaces:**
- Produces: `projectApi.getEmailSettings(projectId)`、`projectApi.putEmailSettings(projectId, items)`

- [ ] **Step 1: 编写失败测试**

创建 `src/services/api/projectEmailSettings.test.ts`：

```typescript
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { projectApi } from './project'

vi.mock('./index', () => ({
  api: {
    get: vi.fn(),
    put: vi.fn()
  },
  unwrap: (res: { data: unknown }) => res.data
}))

describe('projectApi email settings', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset()
    vi.mocked(api.put).mockReset()
  })

  it('getEmailSettings returns scenario flags', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: { code: 200, data: [{ scenarioKey: 'daily_summary', enabled: false }] }
    } as any)

    const result = await projectApi.getEmailSettings(10)

    expect(api.get).toHaveBeenCalledWith('/projects/10/email-settings')
    expect(result).toEqual([{ scenarioKey: 'daily_summary', enabled: false }])
  })

  it('putEmailSettings posts items', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { code: 200, data: null } } as any)

    await projectApi.putEmailSettings(10, [{ scenarioKey: 'daily_summary', enabled: true }])

    expect(api.put).toHaveBeenCalledWith('/projects/10/email-settings', {
      items: [{ scenarioKey: 'daily_summary', enabled: true }]
    })
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm run test -- src/services/api/projectEmailSettings.test.ts`
Expected: FAIL，`projectApi.getEmailSettings` 不是函数。

- [ ] **Step 3: 实现 API 方法**

在 `src/services/api/project.ts` 的 `projectApi` 对象内新增：

```typescript
  getEmailSettings(projectId: number): Promise<{ scenarioKey: string; enabled: boolean }[]> {
    return api
      .get<ApiResponse<{ scenarioKey: string; enabled: boolean }[]>>(`/projects/${projectId}/email-settings`)
      .then((res) => asArray(unwrap(res)))
  },

  putEmailSettings(
    projectId: number,
    items: { scenarioKey: string; enabled: boolean }[]
  ): Promise<void> {
    return api
      .put<ApiResponse<null>>(`/projects/${projectId}/email-settings`, { items })
      .then((res) => {
        unwrap(res)
      })
  },
```

- [ ] **Step 4: 补齐 projectStore.test.ts 与其他 mock**

在 `src/store/projectStore.test.ts` 的 `projectApi` mock 对象内补 `getEmailSettings: vi.fn()`、`putEmailSettings: vi.fn()`，并在 `beforeEach` 中 `mockReset`。同样在 `src/components/TaskEditor.labelSuggestions.test.ts`、`TaskEditorAttachments.test.ts`、`TaskEditorComments.test.ts`、`TaskEditorDueState.test.ts`、`TaskLabelCombobox.test.ts` 的 `projectApi` mock 中补 `getEmailSettings: vi.fn()`、`putEmailSettings: vi.fn()`，避免 mock 缺方法。

- [ ] **Step 5: 运行测试确认通过**

Run: `npm run test -- src/services/api/projectEmailSettings.test.ts src/store/projectStore.test.ts`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/services/api/project.ts src/services/api/projectEmailSettings.test.ts src/store/projectStore.test.ts src/components/TaskEditor.labelSuggestions.test.ts src/components/TaskEditorAttachments.test.ts src/components/TaskEditorComments.test.ts src/components/TaskEditorDueState.test.ts src/components/TaskLabelCombobox.test.ts
git commit -m "feat(email): 前端项目邮件设置 API 与 mock 补齐"
```

---

### Task 11: 项目设置弹窗邮件开关 UI

**Files:**
- Modify: `src/components/ProjectSettingsDialog.vue`
- Modify: `src/components/ProjectSettingsDialog.test.ts`
- Modify: `src/components/ProjectSettingsModal.vue`
- Modify: `src/locales/en.json`、`src/locales/zh-CN.json`
- Modify: `src/SecondaryModalsI18n.test.ts`（若有断言文案键存在则补）

**Interfaces:**
- Consumes: `projectApi.getEmailSettings`、`projectApi.putEmailSettings`
- Produces: `ProjectSettingsDialog` 新增 props `dailySummaryEnabled: boolean`、`isEmailSaving: boolean`、emit `toggleDailySummary`；`ProjectSettingsModal` 加载/保存开关逻辑。

- [ ] **Step 1: 编写失败测试**

在 `src/components/ProjectSettingsDialog.test.ts` 的 props 中补 `dailySummaryEnabled: false`、`isEmailSaving: false`，并在「emits field updates and action events」用例中新增：

```typescript
    ;(host.querySelector('[data-testid="project-settings-daily-summary"]') as HTMLInputElement).checked = true
    ;(host.querySelector('[data-testid="project-settings-daily-summary"]') as HTMLInputElement).dispatchEvent(
      new Event('change', { bubbles: true })
    )
    await nextTick()

    expect(onToggleDailySummary).toHaveBeenCalledWith(true)
```

并新增 `const onToggleDailySummary = vi.fn()` 与 `onToggleDailySummary` 绑定。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm run test -- src/components/ProjectSettingsDialog.test.ts`
Expected: FAIL，`project-settings-daily-summary` 元素不存在。

- [ ] **Step 3: 实现 Dialog UI 与 emit**

在 `ProjectSettingsDialog.vue`：
- props 新增 `dailySummaryEnabled: boolean`、`isEmailSaving: boolean`
- emit 新增 `toggleDailySummary: [value: boolean]`
- 在 `import-zone` 与 `codex-zone` 之间插入：

```html
        <div v-if="canDelete" class="section-panel email-zone">
          <div class="section-header">
            <p class="section-title">{{ t('projectSettingsModal.emailTitle') }}</p>
            <p class="section-text">{{ t('projectSettingsModal.emailDescription') }}</p>
          </div>
          <label class="email-toggle">
            <input
              type="checkbox"
              data-testid="project-settings-daily-summary"
              :checked="dailySummaryEnabled"
              :disabled="isEmailSaving"
              @change="emit('toggleDailySummary', ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ t('projectSettingsModal.dailySummary') }}</span>
          </label>
        </div>
```

并补 `.email-zone` 样式（复用 `.section-panel` 风格）。

- [ ] **Step 4: 接入 ProjectSettingsModal**

在 `ProjectSettingsModal.vue`：
- 新增 `ref<boolean> dailySummaryEnabled`、`ref<boolean> isEmailSaving`
- `watch` 打开时调用 `projectApi.getEmailSettings(project.id)`，按 `scenarioKey === 'daily_summary'` 设置 `dailySummaryEnabled.value`
- 新增 `async function onToggleDailySummary(enabled: boolean)`：乐观更新 `dailySummaryEnabled.value = enabled`，调用 `projectApi.putEmailSettings(project.id, [{ scenarioKey: 'daily_summary', enabled }])`，失败回滚并设 `error`
- 在模板 `<ProjectSettingsDialog>` 上传 `:daily-summary-enabled="dailySummaryEnabled"`、`:is-email-saving="isEmailSaving"`、`@toggle-daily-summary="onToggleDailySummary"`

- [ ] **Step 5: 补 i18n 文案**

在 `src/locales/en.json` 的 `projectSettingsModal` 节点新增：

```json
    "emailTitle": "Email notifications",
    "emailDescription": "Send a daily summary of due and overdue tasks to assignees.",
    "dailySummary": "Daily summary"
```

在 `src/locales/zh-CN.json` 对应节点新增：

```json
    "emailTitle": "邮件通知",
    "emailDescription": "每天向任务负责人发送今日到期与已逾期任务汇总。",
    "dailySummary": "今日汇总"
```

- [ ] **Step 6: 运行测试确认通过**

Run: `npm run test -- src/components/ProjectSettingsDialog.test.ts`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add src/components/ProjectSettingsDialog.vue src/components/ProjectSettingsDialog.test.ts src/components/ProjectSettingsModal.vue src/locales/en.json src/locales/zh-CN.json src/SecondaryModalsI18n.test.ts
git commit -m "feat(email): 项目设置弹窗邮件通知开关"
```

---

### Task 12: 全量验证

- [ ] **Step 1: 后端全量测试**

Run: `cd linear-lite-server && mvn -q test`
Expected: BUILD SUCCESS

- [ ] **Step 2: 前端全量测试与构建**

Run: `npm run test && npm run build`
Expected: 全部通过

- [ ] **Step 3: 手动验证清单**

- 启动服务，登录后打开任一项目设置弹窗，看到「邮件通知」开关
- 开关关闭时为未选中，开启后刷新页面仍为选中
- 非创建者看不到该区域（`canDelete` 为 false）
- 数据库 `project_email_preferences` 存在对应 `enabled=1` 记录
- 修改系统时间为 16:30，观察调度日志输出「今日汇总调度开始」
- 对一个启用项目，给某任务设 `due_date` 为今天且 `status` 非 `done/canceled/duplicate`，等到 16:30 后 `project_email_dispatches` 出现 `status=sent` 记录，邮箱收到 HTML 邮件
- 把同一任务设为终态，再次触发后不产生新发送记录
- 对已 `sent` 记录再次触发调度，不会重复发送

- [ ] **Step 4: 提交收尾**

若手动验证中发现问题，按 fix commit 单独提交；否则无额外提交。

---

## 自检备注

- 设计稿中 `project_email_preferences` / `project_email_dispatches` 两表均在 Task 1 落地。
- 项目创建初始化与既有项目回填在 Task 1（迁移 SQL 回填）与 Task 2（建项目时调用）覆盖。
- 16:30 调度在 Task 7 覆盖。
- HTML + 纯文本在 Task 4 覆盖。
- `app.public-base-url`、`app.mail.from-name` 在 Task 9 覆盖。
- 幂等与失败隔离在 Task 6 覆盖。
- 前端开关与权限在 Task 11 覆盖。
- 测试覆盖终态排除、逾期分类、HTML 转义、链接生成、幂等、无邮箱跳过、非创建者 403。
