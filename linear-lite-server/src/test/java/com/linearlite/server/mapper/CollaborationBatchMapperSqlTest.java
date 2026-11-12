package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linearlite.server.entity.CollaborationBatch;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationBatchMapperSqlTest {
    @Test void batchEntityMapsPersistedColumnsAndCaseSensitiveId() throws Exception {
        MybatisConfiguration config = new MybatisConfiguration();
        TableInfo table = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "batch-entity"),
                CollaborationBatch.class);
        assertEquals("collaboration_batches", table.getTableName());
        assertEquals("batch_id", table.getKeyColumn());
        assertEquals(IdType.INPUT, table.getIdType());
        assertTrue(table.getFieldList().stream().anyMatch(f -> f.getProperty().equals("hostAuthorizationId")
                && f.getColumn().equals("host_authorization_id")));
        assertTrue(table.getFieldList().stream().anyMatch(f -> f.getProperty().equals("runLeaseUntil")
                && f.getColumn().equals("run_lease_until")));
        try (var stream = getClass().getResourceAsStream("/schema.sql")) {
            assertNotNull(stream);
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(schema.contains("batch_id              VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin"));
            assertTrue(schema.contains("batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin"));
        }
    }

    @Test void pageSqlBindsUserProjectAndBothPartsOfCursor() {
        MybatisConfiguration config = new MybatisConfiguration();
        new MapperAnnotationBuilder(config, CollaborationBatchMapper.class).parse();
        var statement = config.getMappedStatement(CollaborationBatchMapper.class.getName() + ".selectPage");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("projectId", 6L);
        parameters.put("userId", 7L);
        parameters.put("limit", 11);
        BoundSql first = statement.getBoundSql(parameters);
        String firstSql = first.getSql().replaceAll("\\s+", " ");
        assertTrue(firstSql.contains("project_id = ? AND user_id = ?"));
        assertFalse(firstSql.contains("created_at <"));
        assertEquals(3, first.getParameterMappings().size());
        parameters.put("beforeCreatedAt", LocalDateTime.of(2026, 11, 10, 8, 0));
        parameters.put("beforeBatchId", "00000000-0000-0000-0000-000000000001");
        BoundSql next = statement.getBoundSql(parameters);
        String nextSql = next.getSql().replaceAll("\\s+", " ");
        assertTrue(nextSql.contains("created_at < ?"));
        assertTrue(nextSql.contains("created_at = ? AND batch_id < ?"));
        assertTrue(nextSql.contains("ORDER BY created_at DESC, batch_id DESC LIMIT ?"));
        assertEquals(6, next.getParameterMappings().size());
        assertEquals("beforeBatchId", next.getParameterMappings().get(4).getProperty());
    }
}
