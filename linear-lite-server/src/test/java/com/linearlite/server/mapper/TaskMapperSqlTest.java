package com.linearlite.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskMapperSqlTest {

    @Test
    void dailySummaryExcludesBacklogTasks() throws NoSuchMethodException {
        Select select = TaskMapper.class
                .getMethod("selectDueForDigest", java.util.List.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class)
                .getAnnotation(Select.class);

        String sql = String.join(" ", select.value());

        assertTrue(sql.contains("LOWER(t.status) NOT IN ('backlog', 'done', 'canceled', 'duplicate')"));
    }
}
