package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpProperties;
import com.linearlite.server.entity.Project;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectDocumentQueryService;
import com.linearlite.server.service.ProjectDocumentAttachmentService;
import com.linearlite.server.service.ProjectService;
import com.linearlite.server.service.TaskCommandService;
import com.linearlite.server.service.TaskCommentService;
import com.linearlite.server.service.TaskQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectService projectService;
    private ProjectDocumentCommandService projectDocumentCommandService;
    private ProjectDocumentAttachmentService projectDocumentAttachmentService;
    private ProjectDocumentQueryService projectDocumentQueryService;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectDocumentCommandService = mock(ProjectDocumentCommandService.class);
        projectDocumentAttachmentService = mock(ProjectDocumentAttachmentService.class);
        projectDocumentQueryService = mock(ProjectDocumentQueryService.class);
        McpProperties properties = new McpProperties();
        properties.setServerName("linear-lite-test");
        properties.setServerVersion("test");
        properties.setToolListTtlMs(60_000L);
        McpToolRegistry registry = new McpToolRegistry(
                objectMapper,
                new McpDocumentImageContentService(
                        new MarkdownToBlockNoteConverter(objectMapper), projectDocumentAttachmentService),
                projectService,
                mock(TaskCommandService.class),
                mock(TaskQueryService.class),
                mock(TaskCommentService.class),
                projectDocumentCommandService,
                projectDocumentQueryService);
        dispatcher = new McpDispatcher(objectMapper, properties, registry);
    }

    @Test
    void initializesStandardMcpSession() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                initializeRequest("1"), null, null, null, null, 7L);

        assertEquals(200, response.status());
        assertEquals("2025-06-18", response.body().path("result").path("protocolVersion").asText());
        assertEquals("linear-lite-test",
                response.body().path("result").path("serverInfo").path("name").asText());
        assertTrue(response.body().path("result").path("capabilities").path("tools").isObject());
    }

    @Test
    void listsAllTenToolsInStableOrder() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("2", "tools/list", "{}"), null, null, null, null, 7L);

        JsonNode tools = response.body().path("result").path("tools");
        assertEquals(10, tools.size());
        assertEquals("list_projects", tools.get(0).path("name").asText());
        assertEquals("list_documents", tools.get(6).path("name").asText());
        assertEquals("get_document", tools.get(7).path("name").asText());
        assertEquals("update_document", tools.get(9).path("name").asText());
        assertTrue(tools.get(2).path("inputSchema").path("properties").has("projectId"));
        assertEquals("array", tools.get(8).path("inputSchema").path("properties").path("images").path("type").asText());
        assertEquals(8, tools.get(8).path("inputSchema").path("properties").path("images").path("maxItems").asInt());
        JsonNode imageSchema = tools.get(8).path("inputSchema").path("properties").path("images").path("items");
        assertTrue(tools.get(8).path("inputSchema").path("properties").path("images")
                .path("description").asText().contains("mcp-image:<ref>"));
        assertEquals(2, imageSchema.path("oneOf").size());
        assertEquals(false, imageSchema.path("oneOf").get(0).path("additionalProperties").asBoolean());
        assertEquals(false, imageSchema.path("oneOf").get(1).path("additionalProperties").asBoolean());
        assertTrue(tools.get(9).path("inputSchema").path("properties").has("images"));
        assertTrue(tools.get(8).path("description").asText().contains("GFM 表格"));
        assertTrue(tools.get(9).path("description").asText().contains("GFM 表格"));
    }

    @Test
    void describesLinearLiteAsTheRequiredProjectManagementEntryPoint() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                request("2b", "tools/list", "{}"), null, null, null, null, 7L);

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
    void acceptsInitializedNotificationWithoutResponseBody() throws Exception {
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                null, null, null, null, 7L);

        assertEquals(202, response.status());
        assertTrue(response.body() == null);
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
                body, null, null, null, null, 7L);

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
                body, null, null, null, null, 7L);

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
                body, null, null, null, null, 9L);

        assertEquals(200, response.status());
        assertEquals(68L, response.body().path("result").path("structuredContent").get(0)
                .path("id").asLong());
        verify(projectDocumentQueryService).listTree(7L, 9L, false);
    }

    @Test
    void invokesGetDocumentWithAuthenticatedUser() throws Exception {
        ProjectDocumentResponse document = new ProjectDocumentResponse(
                68L, 7L, null, null, null, "安全扫描", "正文", java.util.List.of(), 0, 3L,
                9L, 9L, false, null, null, null);
        when(projectDocumentQueryService.getDocument(68L, 9L)).thenReturn(document);

        String body = request("8", "tools/call",
                "{\"name\":\"get_document\",\"arguments\":{\"documentId\":68}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, null, null, null, null, 9L);

        assertEquals(200, response.status());
        assertEquals("正文", response.body().path("result").path("structuredContent")
                .path("content").asText());
        verify(projectDocumentQueryService).getDocument(68L, 9L);
    }

    @Test
    void invokesGetDocumentByTitleWhenTitleIsTheOnlyCondition() throws Exception {
        ProjectDocumentResponse document = new ProjectDocumentResponse(
                68L, 7L, null, null, null, "安全扫描", "正文", java.util.List.of(), 0, 3L,
                9L, 9L, false, null, null, null);
        when(projectDocumentQueryService.getDocumentByTitle("安全扫描", 9L)).thenReturn(document);

        String body = request("9", "tools/call",
                "{\"name\":\"get_document\",\"arguments\":{\"title\":\"安全扫描\"}}");
        McpDispatcher.DispatchResponse response = dispatcher.dispatch(
                body, null, null, null, null, 9L);

        assertEquals(200, response.status());
        assertEquals(68L, response.body().path("result").path("structuredContent")
                .path("id").asLong());
        verify(projectDocumentQueryService).getDocumentByTitle("安全扫描", 9L);
    }

    @Test
    void createsDocumentWithImageThroughMcpContentFactory() throws Exception {
        when(projectDocumentAttachmentService.uploadImage(
                eq(11L), eq("diagram.png"), eq("image/png"), any(byte[].class), any(), eq(7L)))
                .thenReturn(attachment(31L, 11L));
        when(projectDocumentCommandService.createWithContentFactory(
                eq(7L), any(CreateProjectDocumentRequest.class), eq(7L), any(LongFunction.class)))
                .thenAnswer(invocation -> {
                    LongFunction<String> factory = invocation.getArgument(3);
                    return documentResponse(factory.apply(11L));
                });

        McpDispatcher.DispatchResponse response = dispatcher.dispatch(request("10", "tools/call",
                "{\"name\":\"create_document\",\"arguments\":{\"projectId\":7,\"title\":\"设计\","
                        + "\"content\":\"before ![图](mcp-image:fig) after\",\"images\":[{\"ref\":\"fig\","
                        + "\"fileName\":\"diagram.png\",\"contentType\":\"image/png\",\"dataBase64\":\"AQID\"}]}}"),
                null, null, null, null, 7L);

        JsonNode blocks = objectMapper.readTree(response.body().path("result").path("structuredContent")
                .path("content").asText());
        assertEquals("documentImage", blocks.get(1).path("type").asText());
        assertEquals(31L, blocks.get(1).path("props").path("imageAssetId").asLong());
        verify(projectDocumentAttachmentService).uploadImage(
                eq(11L), eq("diagram.png"), eq("image/png"), any(byte[].class), any(), eq(7L));
    }

    @Test
    void createsDocumentWithGfmTableAsNativeBlockNoteTable() throws Exception {
        when(projectDocumentCommandService.createWithContentFactory(
                eq(7L), any(CreateProjectDocumentRequest.class), eq(7L), any(LongFunction.class)))
                .thenAnswer(invocation -> {
                    LongFunction<String> factory = invocation.getArgument(3);
                    return documentResponse(factory.apply(11L));
                });

        McpDispatcher.DispatchResponse response = dispatcher.dispatch(request("10b", "tools/call",
                "{\"name\":\"create_document\",\"arguments\":{\"projectId\":7,\"title\":\"设计\","
                        + "\"content\":\"| 能力 | 职责 |\\n|---|---|\\n| 值与类型 | 声明输入输出类型 |\"}}"),
                null, null, null, null, 7L);

        JsonNode blocks = objectMapper.readTree(response.body().path("result").path("structuredContent")
                .path("content").asText());
        assertEquals(1, blocks.size());
        JsonNode table = blocks.get(0);
        assertEquals("table", table.path("type").asText());
        assertEquals("tableContent", table.path("content").path("type").asText());
        assertEquals(2, table.path("content").path("rows").size());
        assertEquals("值与类型", table.path("content").path("rows").get(1)
                .path("cells").get(0).path("content").get(0).path("text").asText());
    }

    @Test
    void updatesDocumentWithAnAuthorizedExistingImageAsset() throws Exception {
        when(projectDocumentAttachmentService.cloneToDocument(11L, 31L, 7L))
                .thenReturn(attachment(41L, 11L));
        when(projectDocumentCommandService.updateWithContentFactory(
                eq(11L), any(UpdateProjectDocumentRequest.class), eq(7L), any(LongFunction.class)))
                .thenAnswer(invocation -> {
                    LongFunction<String> factory = invocation.getArgument(3);
                    return documentResponse(factory.apply(11L));
                });

        McpDispatcher.DispatchResponse response = dispatcher.dispatch(request("11", "tools/call",
                "{\"name\":\"update_document\",\"arguments\":{\"documentId\":11,\"expectedVersion\":3,"
                        + "\"title\":\"设计\",\"content\":\"![图](mcp-image:reuse)\","
                        + "\"images\":[{\"ref\":\"reuse\",\"assetId\":31}]}}"),
                null, null, null, null, 7L);

        JsonNode blocks = objectMapper.readTree(response.body().path("result").path("structuredContent")
                .path("content").asText());
        assertEquals(41L, blocks.get(0).path("props").path("imageAssetId").asLong());
        verify(projectDocumentAttachmentService).cloneToDocument(11L, 31L, 7L);
    }

    @Test
    void rejectsExternalImageUrlInsteadOfSavingItToDocumentBody() throws Exception {
        when(projectDocumentCommandService.createWithContentFactory(
                eq(7L), any(CreateProjectDocumentRequest.class), eq(7L), any(LongFunction.class)))
                .thenAnswer(invocation -> {
                    LongFunction<String> factory = invocation.getArgument(3);
                    return documentResponse(factory.apply(11L));
                });

        McpDispatcher.DispatchResponse response = dispatcher.dispatch(request("12", "tools/call",
                "{\"name\":\"create_document\",\"arguments\":{\"projectId\":7,\"title\":\"设计\","
                        + "\"content\":\"![外链](https://example.com/image.png)\"}}"),
                null, null, null, null, 7L);

        assertEquals(400, response.status());
        assertEquals(-32602, response.body().path("error").path("code").asInt());
    }

    private ProjectDocumentResponse documentResponse(String content) {
        return new ProjectDocumentResponse(11L, 7L, null, null, null, "设计", content,
                List.of(), 0, 1L, 7L, 7L, false, null, null, null);
    }

    private ProjectDocumentAttachmentResponse attachment(Long id, Long documentId) {
        return new ProjectDocumentAttachmentResponse(id, 7L, documentId, null, "diagram.png", 3L,
                "image/png", "hash", 1, 1, null, null, null);
    }

    private String initializeRequest(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"codex\",\"version\":\"test\"}}}";
    }

    private String request(String id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }
}
