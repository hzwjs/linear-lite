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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationModelMappingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void taskDetailAndListCopyTheSameCollaborationFields() throws Exception {
        Task task = new Task();
        task.setAgentExecutionEnabled(true);
        task.setInputRevision(7L);
        task.setWriteScopes(List.of("src/", "docs/说明.md"));
        task.setPublicContextRefs(json.readTree("[{\"documentId\":101,\"version\":3}]"));

        JsonNode detail = json.valueToTree(task);
        JsonNode list = json.valueToTree(TaskListItemResponse.fromTask(task));
        for (String field : List.of("agentExecutionEnabled", "inputRevision", "writeScopes", "publicContextRefs")) {
            assertEquals(detail.get(field), list.get(field));
        }
        assertEquals("docs/说明.md", list.get("writeScopes").get(1).asText());
    }

    @Test
    void annotationQueriesUseExplicitJsonTypeMappings() throws Exception {
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TaskMapper.class);
        config.addMapper(TaskExecutionAttemptMapper.class);

        assertInstanceOf(StringListJsonTypeHandler.class, mapping(config,
                TaskMapper.class.getName() + ".selectById", "writeScopes").getTypeHandler());
        assertInstanceOf(StringListJsonTypeHandler.class, mapping(config,
                TaskMapper.class.getName() + ".selectByIdForUpdate", "writeScopes").getTypeHandler());
        assertInstanceOf(StringListJsonTypeHandler.class, mapping(config,
                TaskMapper.class.getName() + ".selectListItemResponses", "writeScopes").getTypeHandler());
        for (String method : List.of("selectByIdForUpdate", "selectListItems", "selectListItemResponses", "selectMcpProjectTasks")) {
            assertInstanceOf(JacksonTypeHandler.class, mapping(config,
                    TaskMapper.class.getName() + "." + method, "publicContextRefs").getTypeHandler());
        }
        assertInstanceOf(StringListJsonTypeHandler.class, mapping(config,
                TaskMapper.class.getName() + ".selectMcpProjectTasks", "writeScopes").getTypeHandler());
        assertInstanceOf(StringListJsonTypeHandler.class, mapping(config,
                TaskExecutionAttemptMapper.class.getName() + ".selectByIdForUpdate", "heldWriteScopes").getTypeHandler());
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
        attempt.setHeldWriteScopes(List.of("src/"));
        attempt.setPrerequisiteVersions(json.readTree("[{\"taskId\":1,\"inputRevision\":2}]"));
        attempt.setCheckpoints(json.readTree("[{\"operationId\":\"one\",\"artifactRefs\":[\"docs/a.md\"]}]"));
        attempt.setResultEvidence(json.readTree("{\"revision\":\"abc\"}"));

        JsonNode response = json.valueToTree(TaskExecutionAttemptResponse.from(attempt));
        assertEquals(2, response.at("/prerequisiteVersions/0/inputRevision").asInt());
        assertEquals("docs/a.md", response.at("/checkpoints/0/artifactRefs/0").asText());
        assertEquals("abc", response.at("/resultEvidence/revision").asText());
        assertFalse(response.has("claimTokenHash"));
        assertFalse(json.valueToTree(attempt).has("claimTokenHash"));
    }

    private ResultMapping mapping(MybatisConfiguration config, String statement, String property) {
        ResultMap resultMap = config.getMappedStatement(statement).getResultMaps().get(0);
        return resultMap.getResultMappings().stream()
                .filter(item -> property.equals(item.getProperty())).findFirst().orElseThrow();
    }
}
