package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpProperties;
import com.linearlite.server.entity.Project;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectDocumentQueryService;
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
    private ProjectDocumentQueryService projectDocumentQueryService;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectDocumentQueryService = mock(ProjectDocumentQueryService.class);
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
                mock(ProjectDocumentCommandService.class),
                projectDocumentQueryService);
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
                response.body().path("result").path("serverInfo").path("name").asText());
        assertTrue(response.body().path("result").path("_meta")
                .path("io.modelcontextprotocol/serverInfo").isMissingNode());
    }

    @Test
    void listsAllTenToolsInStableOrder() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("2", "tools/list", "{}"),
                "2026-07-28", "tools/list", null, null, 7L);

        JsonNode tools = response.body().path("result").path("tools");
        assertEquals(10, tools.size());
        assertEquals("list_projects", tools.get(0).path("name").asText());
        assertEquals("list_documents", tools.get(6).path("name").asText());
        assertEquals("get_document", tools.get(7).path("name").asText());
        assertEquals("update_document", tools.get(9).path("name").asText());
        assertEquals("private", response.body().path("result").path("cacheScope").asText());
        assertTrue(tools.get(2).path("inputSchema").path("properties").has("projectId"));
    }

    @Test
    void describesLinearLiteAsTheRequiredProjectManagementEntryPoint() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("2b", "tools/list", "{}"),
                "2026-07-28", "tools/list", null, null, 7L);

        JsonNode tools = response.body().path("result").path("tools");
        for (JsonNode tool : tools) {
            String description = tool.path("description").asText();
            assertTrue(description.startsWith("用于 Linear Lite"));
            assertTrue(description.contains("必须优先调用此工具"));
            assertTrue(description.contains("不要使用浏览器"));
            assertTrue(description.contains("mcp__linear_lite_*"));
        }
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

    @Test
    void invokesListDocumentsWithAuthenticatedUser() throws Exception {
        ProjectDocumentTreeNode document = new ProjectDocumentTreeNode(
                68L, 7L, null, "安全扫描", 0, 3L, true, null);
        when(projectDocumentQueryService.listTree(7L, 9L, false))
                .thenReturn(java.util.List.of(document));

        String body = request("7", "tools/call",
                "{\"name\":\"list_documents\",\"arguments\":{\"projectId\":7}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, "2026-07-28", "tools/call", "list_documents", null, 9L);

        assertEquals(200, response.status());
        assertEquals(68L, response.body().path("result").path("structuredContent").get(0)
                .path("id").asLong());
        verify(projectDocumentQueryService).listTree(7L, 9L, false);
    }

    @Test
    void invokesGetDocumentWithAuthenticatedUser() throws Exception {
        ProjectDocumentResponse document = new ProjectDocumentResponse(
                68L, 7L, null, null, null, "安全扫描", "正文", 0, 3L,
                9L, 9L, false, null, null, null);
        when(projectDocumentQueryService.getDocument(68L, 9L)).thenReturn(document);

        String body = request("8", "tools/call",
                "{\"name\":\"get_document\",\"arguments\":{\"documentId\":68}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, "2026-07-28", "tools/call", "get_document", null, 9L);

        assertEquals(200, response.status());
        assertEquals("正文", response.body().path("result").path("structuredContent")
                .path("content").asText());
        verify(projectDocumentQueryService).getDocument(68L, 9L);
    }

    @Test
    void invokesGetDocumentByTitleWhenTitleIsTheOnlyCondition() throws Exception {
        ProjectDocumentResponse document = new ProjectDocumentResponse(
                68L, 7L, null, null, null, "安全扫描", "正文", 0, 3L,
                9L, 9L, false, null, null, null);
        when(projectDocumentQueryService.getDocumentByTitle("安全扫描", 9L)).thenReturn(document);

        String body = request("9", "tools/call",
                "{\"name\":\"get_document\",\"arguments\":{\"title\":\"安全扫描\"}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, "2026-07-28", "tools/call", "get_document", null, 9L);

        assertEquals(200, response.status());
        assertEquals(68L, response.body().path("result").path("structuredContent")
                .path("id").asLong());
        verify(projectDocumentQueryService).getDocumentByTitle("安全扫描", 9L);
    }

    private String request(String id, String method, String params) {
        String extraParams = "{}".equals(params) ? "}}" : "," + params.substring(1) + "}";
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method
                + "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}" + extraParams;
    }
}
