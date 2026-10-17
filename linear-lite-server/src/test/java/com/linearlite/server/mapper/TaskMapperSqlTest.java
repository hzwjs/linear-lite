package com.linearlite.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskMapperSqlTest {

    @Test
    void dailySummaryExcludesBacklogTasks() throws NoSuchMethodException {
        Select select = TaskMapper.class
                .getMethod("selectDueForDigest", java.util.List.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class)
                .getAnnotation(Select.class);

        String sql = String.join(" ", select.value());

        assertTrue(sql.contains("LOWER(t.status) NOT IN ('backlog', 'done', 'canceled', 'duplicate')"));
        assertTrue(sql.contains("#{completedWindowStart}"));
        assertTrue(sql.contains("#{completedWindowEnd}"));
    }

    @Test
    void mcpProjectTaskQueryUsesProjectScopeAndStableKeysetOrder() throws NoSuchMethodException {
        Select select = TaskMapper.class.getMethod("selectMcpProjectTasks",
                Long.class, Long.class, String.class, Long.class, int.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertTrue(sql.contains("t.project_id = #{projectId}"));
        assertTrue(sql.contains("t.id &gt; #{afterId}"));
        assertTrue(sql.contains("ORDER BY t.id ASC"));
        assertTrue(sql.contains("t.progress_percent AS progressPercent"));
        assertTrue(sql.contains("t.assignee_display_name AS assigneeDisplayName"));
        assertTrue(sql.contains("LIMIT #{fetchLimit}"));
    }

    @Test
    void mcpTaskChangeQueryReadsPersistedChangesWithShanghaiRangeAndCursor() throws NoSuchMethodException {
        Select select = TaskActivityMapper.class.getMethod("selectMcpProjectTaskChanges",
                Long.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                java.util.List.class, Long.class, int.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertTrue(sql.contains("FROM task_activities a"));
        assertTrue(sql.contains("t.project_id = #{projectId}"));
        assertTrue(sql.contains("a.action_type = 'changed'"));
        assertTrue(sql.contains("a.created_at &gt;= #{from}"));
        assertTrue(sql.contains("a.created_at &lt; #{to}"));
        assertTrue(sql.contains("a.field_name IN"));
        assertTrue(sql.contains("a.id &gt; #{afterId}"));
        assertTrue(sql.contains("ORDER BY a.id ASC"));
        assertTrue(sql.contains("u.username AS actorDisplayName"));
        assertTrue(sql.contains("LIMIT #{fetchLimit}"));
    }
}
