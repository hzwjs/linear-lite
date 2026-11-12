package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationModelMappingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void taskDetailAndListCopyTheSameCollaborationFields() throws Exception {
        Task task = new Task();
        task.setInputRevision(7L);
        JsonNode detail = json.valueToTree(task);
        JsonNode list = json.valueToTree(TaskListItemResponse.fromTask(task));
        assertEquals(detail.get("inputRevision"), list.get("inputRevision"));
        for (String field : List.of("agentExecutionEnabled", "writeScopes", "publicContextRefs")) {
            assertFalse(detail.has(field)); assertFalse(list.has(field));
        }
    }

    @Test
    void annotationQueriesUseExplicitJsonTypeMappings() throws Exception {
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TaskMapper.class);
        config.addMapper(TaskExecutionAttemptMapper.class);

        assertInstanceOf(JacksonTypeHandler.class, mapping(config,
                TaskExecutionAttemptMapper.class.getName() + ".selectByIdForUpdate", "checkpoints").getTypeHandler());
        assertInstanceOf(JacksonTypeHandler.class, mapping(config,
                TaskExecutionAttemptMapper.class.getName() + ".selectById", "checkpoints").getTypeHandler());
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("checkpoints"))
                .thenReturn("[{\"operationId\":\"one\",\"artifactRefs\":[\"docs/a.md\"]}]");
        Object parsed = mapping(config, TaskExecutionAttemptMapper.class.getName() + ".selectByIdForUpdate",
                "checkpoints").getTypeHandler().getResult(resultSet, "checkpoints");
        JsonNode checkpoint = assertInstanceOf(JsonNode.class, parsed);
        assertEquals("docs/a.md", checkpoint.at("/0/artifactRefs/0").asText());
    }

    @Test
    void attemptResponsePreservesNestedFactsAndExcludesClaimMaterial() throws Exception {
        TaskExecutionAttempt attempt = new TaskExecutionAttempt();
        attempt.setClaimTokenHash("secret-digest");
        attempt.setInputRevision(3L);
        attempt.setPrerequisiteVersions(json.readTree("[{\"taskId\":1,\"inputRevision\":2}]"));
        attempt.setCheckpoints(json.readTree("[{\"operationId\":\"one\",\"artifactRefs\":[\"docs/a.md\"]}]"));
        attempt.setResultEvidence(json.readTree("{\"revision\":\"abc\"}"));

        JsonNode response = json.valueToTree(TaskExecutionAttemptResponse.from(attempt));
        assertEquals(2, response.at("/prerequisiteVersions/0/inputRevision").asInt());
        assertEquals("docs/a.md", response.at("/checkpoints/0/artifactRefs/0").asText());
        assertEquals("abc", response.at("/resultEvidence/revision").asText());
        assertFalse(response.has("heldWriteScopes"));
        assertFalse(response.has("claimTokenHash"));
        assertFalse(json.valueToTree(attempt).has("claimTokenHash"));
    }

    @Test
    void participationIncludesQueuedBatchAndDoesNotDependOnRemovedConfiguration() throws Exception {
        var select = TaskDependencyMapper.class.getMethod("hasCollaborationParticipation", Long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("collaboration_batch_tasks"));
        assertTrue(sql.contains("JOIN collaboration_batches"));
        assertTrue(sql.contains("task_execution_attempts"));
        assertTrue(sql.contains("task_dependencies"));
        assertFalse(sql.contains("agent_execution_enabled"));
        assertFalse(sql.contains("public_context_refs"));
    }

    private ResultMapping mapping(MybatisConfiguration config, String statement, String property) {
        ResultMap resultMap = config.getMappedStatement(statement).getResultMaps().get(0);
        return resultMap.getResultMappings().stream()
                .filter(item -> property.equals(item.getProperty())).findFirst().orElseThrow();
    }
}
