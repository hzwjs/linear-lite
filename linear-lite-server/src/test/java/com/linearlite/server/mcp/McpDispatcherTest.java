package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpProperties;
import com.linearlite.server.entity.Project;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectService;
import com.linearlite.server.service.TaskCommandService;
import com.linearlite.server.service.TaskCommentService;
import com.linearlite.server.service.TaskQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectService projectService;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        McpProperties properties = new McpProperties();
        properties.setServerName("linear-lite-test");
        properties.setServerVersion("test");
        properties.setToolListTtlMs(60_000L);
        McpToolRegistry registry = new McpToolRegistry(
                objectMapper,
                new MarkdownToBlockNoteConverter(objectMapper),
                projectService,
                mock(TaskCommandService.class),
                mock(TaskQueryService.class),
                mock(TaskCommentService.class),
                mock(ProjectDocumentCommandService.class));
        dispatcher = new McpDispatcher(objectMapper, properties, registry);
    }

    @Test
    void discoversOnlyThe20260728Protocol() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("1", "server/discover", "{}"),
                "2026-07-28", "server/discover", null, null, 7L);

        assertEquals(200, response.status());
        assertEquals("2026-07-28", response.body().path("result").path("supportedVersions").get(0).asText());
        assertEquals("linear-lite-test",
                response.body().path("result").path("_meta")
                        .path("io.modelcontextprotocol/serverInfo").path("name").asText());
    }

    @Test
    void listsAllEightToolsInStableOrder() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("2", "tools/list", "{}"),
                "2026-07-28", "tools/list", null, null, 7L);

        JsonNode tools = response.body().path("result").path("tools");
        assertEquals(8, tools.size());
        assertEquals("list_projects", tools.get(0).path("name").asText());
        assertEquals("update_document", tools.get(7).path("name").asText());
        assertEquals("private", response.body().path("result").path("cacheScope").asText());
        assertTrue(tools.get(2).path("inputSchema").path("properties").has("projectId"));
    }

    @Test
    void rejectsLegacyInitializeInsteadOfStartingACompatibilitySession() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("3", "initialize", "{}"),
                "2026-07-28", "initialize", null, null, 7L);

        assertEquals(404, response.status());
        assertEquals(-32601, response.body().path("error").path("code").asInt());
    }

    @Test
    void rejectsHeaderAndBodyProtocolMismatch() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("4", "tools/list", "{}"),
                "2026-07-28", "tools/call", null, null, 7L);

        assertEquals(400, response.status());
        assertEquals(-32020, response.body().path("error").path("code").asInt());
    }

    @Test
    void invokesCreateProjectWithAuthenticatedUser() throws Exception {
        Project project = new Project();
        project.setId(11L);
        project.setName("Engineering");
        project.setIdentifier("ENG");
        when(projectService.create("Engineering", "ENG", 7L)).thenReturn(project);

        String body = request("5", "tools/call",
                "{\"name\":\"create_project\",\"arguments\":{\"name\":\"Engineering\",\"identifier\":\"ENG\"}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, "2026-07-28", "tools/call", "create_project", null, 7L);

        assertEquals(200, response.status());
        assertEquals("ENG", response.body().path("result").path("structuredContent").path("identifier").asText());
        assertNotNull(response.body().path("result").path("content").get(0));
        verify(projectService).create(eq("Engineering"), eq("ENG"), eq(7L));
    }

    @Test
    void invokesListProjectsWithAuthenticatedUser() throws Exception {
        Project project = new Project();
        project.setId(11L);
        project.setName("Engineering");
        project.setIdentifier("ENG");
        when(projectService.list(7L)).thenReturn(java.util.List.of(project));

        String body = request("6", "tools/call",
                "{\"name\":\"list_projects\",\"arguments\":{}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, "2026-07-28", "tools/call", "list_projects", null, 7L);

        assertEquals(200, response.status());
        assertEquals(1, response.body().path("result").path("structuredContent").size());
        assertEquals("ENG", response.body().path("result").path("structuredContent").get(0)
                .path("identifier").asText());
        verify(projectService).list(7L);
    }

    private String request(String id, String method, String params) {
        String extraParams = "{}".equals(params) ? "}}" : "," + params.substring(1) + "}";
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method
                + "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}" + extraParams;
    }
}
