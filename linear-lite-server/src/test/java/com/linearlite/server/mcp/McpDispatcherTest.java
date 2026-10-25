package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.McpProperties;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.entity.Project;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.TaskChangeRecord;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectDocumentQueryService;
import com.linearlite.server.service.ProjectDocumentAttachmentService;
import com.linearlite.server.service.TaskActivityService;
import com.linearlite.server.service.ProjectService;
import com.linearlite.server.service.TaskCommandService;
import com.linearlite.server.service.TaskCommentService;
import com.linearlite.server.service.TaskQueryService;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class McpDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectService projectService;
    private ProjectDocumentCommandService projectDocumentCommandService;
    private ProjectDocumentAttachmentService projectDocumentAttachmentService;
    private ProjectDocumentQueryService projectDocumentQueryService;
    private com.linearlite.server.service.McpDocumentPatchService documentPatchService;
    private TaskQueryService taskQueryService;
    private TaskActivityService taskActivityService;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        projectDocumentCommandService = mock(ProjectDocumentCommandService.class);
        projectDocumentAttachmentService = mock(ProjectDocumentAttachmentService.class);
        projectDocumentQueryService = mock(ProjectDocumentQueryService.class);
        documentPatchService = mock(com.linearlite.server.service.McpDocumentPatchService.class);
        taskQueryService = mock(TaskQueryService.class);
        taskActivityService = mock(TaskActivityService.class);
        McpProperties properties = new McpProperties();
        properties.setServerName("linear-lite-test");
        properties.setServerVersion("test");
        properties.setToolListTtlMs(60_000L);
        McpToolRegistry registry = new McpToolRegistry(
                objectMapper,
                new MarkdownToBlockNoteConverter(objectMapper),
                new McpDocumentImageContentService(
                        new MarkdownToBlockNoteConverter(objectMapper), projectDocumentAttachmentService),
                projectService,
                mock(TaskCommandService.class),
                taskQueryService,
                taskActivityService,
                mock(TaskCommentService.class),
                mock(McpTaskCommentImageContentService.class),
                projectDocumentCommandService,
                projectDocumentQueryService,
                documentPatchService,
                mock(UserMapper.class));
        dispatcher = new McpDispatcher(objectMapper, properties, new McpOAuthProperties(), registry);
    }

    @Test
    void initializesStandardMcpSession() throws Exception {
        McpDispatcher.DispatchResponse response = dispatch(
                initializeRequest("1"), null, null, null, null, 7L);

        assertEquals(200, response.status());
        assertEquals("2025-06-18", response.body().path("result").path("protocolVersion").asText());
        assertEquals("linear-lite-test",
                response.body().path("result").path("serverInfo").path("name").asText());
        assertTrue(response.body().path("result").path("capabilities").path("tools").isObject());
    }

    @Test
    void listsAllSeventeenToolsInStableOrder() throws Exception {
        McpDispatcher.DispatchResponse response = dispatch(
                request("2", "tools/list", "{}"), null, null, null, null, 7L);

        JsonNode tools = response.body().path("result").path("tools");
        assertEquals(17, tools.size());
        assertEquals("get_profile", tools.get(0).path("name").asText());
        assertEquals("list_projects", tools.get(1).path("name").asText());
        assertEquals("list_task_comments", tools.get(6).path("name").asText());
        JsonNode addComment = tools.get(5);
        assertTrue(addComment.path("annotations").path("idempotentHint").asBoolean());
        assertTrue(addComment.path("inputSchema").path("required").toString().contains("operationId"));
        assertTrue(tools.get(6).path("annotations").path("readOnlyHint").asBoolean());
        assertEquals("list_project_tasks", tools.get(8).path("name").asText());
        assertEquals("list_task_changes", tools.get(9).path("name").asText());
        assertEquals("list_documents", tools.get(10).path("name").asText());
        assertEquals("get_document", tools.get(11).path("name").asText());
        assertEquals("get_document_blocks", tools.get(12).path("name").asText());
        assertEquals("create_document", tools.get(13).path("name").asText());
        assertEquals("update_document", tools.get(14).path("name").asText());
        assertEquals("patch_document", tools.get(15).path("name").asText());
        assertEquals("get_member_id", tools.get(16).path("name").asText());
        assertTrue(tools.get(16).path("inputSchema").path("properties").has("username"));
        assertTrue(tools.get(3).path("inputSchema").path("properties").has("projectId"));
        assertEquals("array", tools.get(13).path("inputSchema").path("properties").path("images").path("type").asText());
        assertEquals(8, tools.get(13).path("inputSchema").path("properties").path("images").path("maxItems").asInt());
        JsonNode imageSchema = tools.get(13).path("inputSchema").path("properties").path("images").path("items");
        assertTrue(tools.get(13).path("inputSchema").path("properties").path("images")
                .path("description").asText().contains("mcp-image:<ref>"));
        assertEquals(2, imageSchema.path("oneOf").size());
        assertEquals(false, imageSchema.path("oneOf").get(0).path("additionalProperties").asBoolean());
        assertEquals(false, imageSchema.path("oneOf").get(1).path("additionalProperties").asBoolean());
        assertTrue(tools.get(14).path("inputSchema").path("properties").has("images"));
        assertTrue(tools.get(13).path("description").asText().contains("GFM 表格"));
        assertTrue(tools.get(14).path("description").asText().contains("GFM 表格"));
        assertTrue(tools.get(15).path("inputSchema").path("properties").has("operations"));
        assertEquals(50, tools.get(15).path("inputSchema").path("properties").path("operations").path("maxItems").asInt());
        JsonNode patchOperations = tools.get(15).path("inputSchema").path("properties").path("operations")
                .path("items").path("oneOf");
        assertEquals(8, patchOperations.size());
        assertEquals("insert_table_row", patchOperations.get(5).path("properties").path("op").path("enum").get(0).asText());
    }

    @Test
    void projectTaskReadToolsExposeReadOnlyPaginationAndDailyRules() throws Exception {
        McpDispatcher.DispatchResponse response = dispatch(
                request("read-contract", "tools/list", "{}"), null, null, null, null, 9L);
        JsonNode tools = response.body().path("result").path("tools");
        JsonNode taskList = tools.get(8);
        JsonNode changes = tools.get(9);

        assertEquals("list_project_tasks", taskList.path("name").asText());
        assertTrue(taskList.path("annotations").path("readOnlyHint").asBoolean());
        assertEquals("linear-lite:read", taskList.path("securitySchemes").get(0).path("scopes").get(0).asText());
        assertTrue(taskList.path("inputSchema").path("properties").has("cursor"));
        assertTrue(taskList.path("inputSchema").path("properties").has("limit"));
        assertTrue(taskList.path("inputSchema").path("properties").has("status"));
        assertTrue(taskList.path("inputSchema").path("properties").has("assigneeId"));
        assertTrue(taskList.path("description").asText().contains("0% 任务"));

        assertEquals("list_task_changes", changes.path("name").asText());
        assertTrue(changes.path("annotations").path("readOnlyHint").asBoolean());
        assertEquals("linear-lite:read", changes.path("securitySchemes").get(0).path("scopes").get(0).asText());
        assertEquals("date-time", changes.path("inputSchema").path("properties").path("from").path("format").asText());
        assertTrue(changes.path("description").asText().contains("Asia/Shanghai"));
        assertTrue(changes.path("description").asText().contains("当前 0% 任务排除，含 status 变化"));
        assertTrue(changes.path("description").asText().contains("0% 升至正数"));
        assertTrue(changes.path("description").asText().contains("updatedAt 变化不代表进度变化"));
    }

    @Test
    void listProjectTasksPaginatesWithoutDuplicatesAndPreservesZeroProgress() throws Exception {
        when(taskQueryService.listProjectTasksForMcp(37L, null, 2, null, null, 9L))
                .thenReturn(List.of(taskItem(1L, "EASYGO-1", "backlog", 20, 3L),
                        taskItem(2L, "EASYGO-2", "done", 0, null),
                        taskItem(3L, "EASYGO-3", "todo", 55, 4L)));
        when(taskQueryService.listProjectTasksForMcp(37L, 2L, 2, null, null, 9L))
                .thenReturn(List.of(taskItem(3L, "EASYGO-3", "todo", 55, 4L)));

        McpDispatcher.DispatchResponse first = dispatch(request("tasks-1", "tools/call",
                "{\"name\":\"list_project_tasks\",\"arguments\":{\"projectId\":37,\"limit\":2}}"),
                null, null, null, null, 9L);
        JsonNode firstContent = first.body().path("result").path("structuredContent");
        assertTrue(firstContent.path("hasMore").asBoolean());
        assertEquals("2", firstContent.path("nextCursor").asText());
        assertEquals(2, firstContent.path("items").size());
        assertEquals(0, firstContent.path("items").get(1).path("progressPercent").asInt());
        assertTrue(firstContent.path("items").get(1).path("assigneeId").isNull());

        McpDispatcher.DispatchResponse second = dispatch(request("tasks-2", "tools/call",
                "{\"name\":\"list_project_tasks\",\"arguments\":{\"projectId\":37,\"limit\":2,\"cursor\":\"2\"}}"),
                null, null, null, null, 9L);
        JsonNode secondContent = second.body().path("result").path("structuredContent");
        assertFalse(secondContent.path("hasMore").asBoolean());
        assertTrue(secondContent.path("nextCursor").isNull());

        List<Long> ids = List.of(
                firstContent.path("items").get(0).path("id").asLong(),
                firstContent.path("items").get(1).path("id").asLong(),
                secondContent.path("items").get(0).path("id").asLong());
        assertEquals(List.of(1L, 2L, 3L), ids);
        verify(taskQueryService).listProjectTasksForMcp(37L, null, 2, null, null, 9L);
        verify(taskQueryService).listProjectTasksForMcp(37L, 2L, 2, null, null, 9L);
    }

    @Test
    void listProjectTasksReturnsAnEmptyTerminalPage() throws Exception {
        when(taskQueryService.listProjectTasksForMcp(37L, null, 100, null, null, 9L)).thenReturn(List.of());

        McpDispatcher.DispatchResponse response = dispatch(request("tasks-empty", "tools/call",
                "{\"name\":\"list_project_tasks\",\"arguments\":{\"projectId\":37}}"),
                null, null, null, null, 9L);
        JsonNode content = response.body().path("result").path("structuredContent");

        assertEquals(0, content.path("items").size());
        assertFalse(content.path("hasMore").asBoolean());
        assertTrue(content.path("nextCursor").isNull());
    }

    @Test
    void listProjectTasksPassesOptionalStateAndAssigneeFilters() throws Exception {
        when(taskQueryService.listProjectTasksForMcp(37L, null, 25, "in_progress", 9L, 9L))
                .thenReturn(List.of(taskItem(8L, "EASYGO-8", "in_progress", 35, 9L)));

        McpDispatcher.DispatchResponse response = dispatch(request("task-filters", "tools/call",
                "{\"name\":\"list_project_tasks\",\"arguments\":{\"projectId\":37,\"limit\":25,\"status\":\"in_progress\",\"assigneeId\":9}}"),
                null, null, null, null, 9L);

        assertEquals(8L, response.body().path("result").path("structuredContent")
                .path("items").get(0).path("id").asLong());
        verify(taskQueryService).listProjectTasksForMcp(37L, null, 25, "in_progress", 9L, 9L);
    }

    @Test
    void listTaskChangesUsesShanghaiHalfOpenBoundsAndReturnsActorAndOffset() throws Exception {
        TaskChangeRecord change = new TaskChangeRecord();
        change.setId(45L);
        change.setTaskId(121L);
        change.setTaskKey("EASYGO-9");
        change.setField("progressPercent");
        change.setOldValue("0");
        change.setNewValue("25");
        change.setChangedAt(LocalDateTime.of(2026, 10, 14, 0, 0));
        change.setActorId(9L);
        change.setActorDisplayName("alice");
        when(taskActivityService.listProjectTaskChangesForMcp(37L,
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0),
                List.of("progressPercent", "status"), null, 100, 9L)).thenReturn(List.of(change));

        McpDispatcher.DispatchResponse response = dispatch(request("changes", "tools/call",
                "{\"name\":\"list_task_changes\",\"arguments\":{\"projectId\":37,\"from\":\"2026-10-13T16:00:00Z\",\"to\":\"2026-10-14T16:00:00Z\"}}"),
                null, null, null, null, 9L);
        JsonNode content = response.body().path("result").path("structuredContent");
        JsonNode item = content.path("items").get(0);

        assertEquals("Asia/Shanghai", content.path("timezone").asText());
        assertEquals("2026-10-14T00:00:00+08:00", content.path("from").asText());
        assertEquals("2026-10-15T00:00:00+08:00", content.path("to").asText());
        assertEquals(121L, item.path("taskId").asLong());
        assertEquals("progressPercent", item.path("field").asText());
        assertEquals("0", item.path("oldValue").asText());
        assertEquals("25", item.path("newValue").asText());
        assertEquals("2026-10-14T00:00:00+08:00", item.path("changedAt").asText());
        assertEquals(9L, item.path("actor").path("id").asLong());
        assertEquals("alice", item.path("actor").path("displayName").asText());
        assertTrue(content.path("historyNote").asText().contains("中间值无法还原"));
        verify(taskActivityService).listProjectTaskChangesForMcp(37L,
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0),
                List.of("progressPercent", "status"), null, 100, 9L);
    }

    @Test
    void listTaskChangesPaginatesByActivityCursor() throws Exception {
        TaskChangeRecord firstChange = taskChange(41L, "status", "todo", "in_progress");
        TaskChangeRecord secondChange = taskChange(42L, "progressPercent", "0", "15");
        when(taskActivityService.listProjectTaskChangesForMcp(37L,
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0),
                List.of("status", "progressPercent"), null, 1, 9L))
                .thenReturn(List.of(firstChange, secondChange));
        when(taskActivityService.listProjectTaskChangesForMcp(37L,
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0),
                List.of("status", "progressPercent"), 41L, 1, 9L))
                .thenReturn(List.of(secondChange));

        String firstArgs = "{\"projectId\":37,\"from\":\"2026-10-14T00:00:00+08:00\","
                + "\"to\":\"2026-10-15T00:00:00+08:00\",\"fields\":[\"status\",\"progressPercent\"],\"limit\":1}";
        McpDispatcher.DispatchResponse first = dispatch(request("change-page-1", "tools/call",
                "{\"name\":\"list_task_changes\",\"arguments\":" + firstArgs + "}"),
                null, null, null, null, 9L);
        JsonNode firstContent = first.body().path("result").path("structuredContent");
        assertTrue(firstContent.path("hasMore").asBoolean());
        assertEquals("41", firstContent.path("nextCursor").asText());
        assertEquals(41L, firstContent.path("items").get(0).path("taskId").asLong());

        String secondArgs = "{\"projectId\":37,\"from\":\"2026-10-14T00:00:00+08:00\","
                + "\"to\":\"2026-10-15T00:00:00+08:00\",\"fields\":[\"status\",\"progressPercent\"],"
                + "\"limit\":1,\"cursor\":\"41\"}";
        McpDispatcher.DispatchResponse second = dispatch(request("change-page-2", "tools/call",
                "{\"name\":\"list_task_changes\",\"arguments\":" + secondArgs + "}"),
                null, null, null, null, 9L);
        JsonNode secondContent = second.body().path("result").path("structuredContent");
        assertFalse(secondContent.path("hasMore").asBoolean());
        assertEquals(42L, secondContent.path("items").get(0).path("taskId").asLong());
        verify(taskActivityService).listProjectTaskChangesForMcp(37L,
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0),
                List.of("status", "progressPercent"), 41L, 1, 9L);
    }

    @Test
    void listTaskChangesReturnsAnEmptyTerminalPage() throws Exception {
        when(taskActivityService.listProjectTaskChangesForMcp(37L,
                LocalDateTime.of(2026, 10, 14, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0),
                List.of("progressPercent", "status"), null, 100, 9L)).thenReturn(List.of());

        McpDispatcher.DispatchResponse response = dispatch(request("changes-empty", "tools/call",
                "{\"name\":\"list_task_changes\",\"arguments\":{\"projectId\":37,"
                        + "\"from\":\"2026-10-14T00:00:00+08:00\",\"to\":\"2026-10-15T00:00:00+08:00\"}}"),
                null, null, null, null, 9L);
        JsonNode content = response.body().path("result").path("structuredContent");

        assertEquals(0, content.path("items").size());
        assertFalse(content.path("hasMore").asBoolean());
        assertTrue(content.path("nextCursor").isNull());
    }

    @Test
    void listTaskChangesRejectsLocalTimesWithoutAnOffset() throws Exception {
        McpDispatcher.DispatchResponse response = dispatch(request("changes-no-offset", "tools/call",
                "{\"name\":\"list_task_changes\",\"arguments\":{\"projectId\":37,\"from\":\"2026-10-14T00:00:00\",\"to\":\"2026-10-15T00:00:00+08:00\"}}"),
                null, null, null, null, 9L);

        assertEquals(400, response.status());
        assertTrue(response.body().has("error"));
        verifyNoInteractions(taskActivityService);
    }

    @Test
    void describesLinearLiteAsTheRequiredProjectManagementEntryPoint() throws Exception {
        McpDispatcher.DispatchResponse response = dispatch(
                request("2b", "tools/list", "{}"), null, null, null, null, 7L);

        JsonNode tools = response.body().path("result").path("tools");
        for (JsonNode tool : tools) {
            if ("get_profile".equals(tool.path("name").asText())) {
                continue;
            }
            String description = tool.path("description").asText();
            assertTrue(description.startsWith("用于 Linear Lite"));
            assertTrue(description.contains("必须优先调用此工具"));
            assertTrue(description.contains("不要使用浏览器"));
            assertTrue(description.contains("mcp__linear_lite_*"));
        }
    }

    @Test
    void acceptsInitializedNotificationWithoutResponseBody() throws Exception {
        McpDispatcher.DispatchResponse response = dispatch(
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
        McpDispatcher.DispatchResponse response = dispatch(
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
        McpDispatcher.DispatchResponse response = dispatch(
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
                68L, 7L, null, "安全扫描", 0, 3L, true, null, "BLOCKNOTE");
        when(projectDocumentQueryService.listTree(7L, 9L, false))
                .thenReturn(java.util.List.of(document));

        String body = request("7", "tools/call",
                "{\"name\":\"list_documents\",\"arguments\":{\"projectId\":7}}");
        McpDispatcher.DispatchResponse response = dispatch(
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
                9L, 9L, false, null, null, null, "BLOCKNOTE");
        when(projectDocumentQueryService.getDocument(68L, 9L)).thenReturn(document);

        String body = request("8", "tools/call",
                "{\"name\":\"get_document\",\"arguments\":{\"documentId\":68}}");
        McpDispatcher.DispatchResponse response = dispatch(
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
                9L, 9L, false, null, null, null, "BLOCKNOTE");
        when(projectDocumentQueryService.getDocumentByTitle("安全扫描", 9L)).thenReturn(document);

        String body = request("9", "tools/call",
                "{\"name\":\"get_document\",\"arguments\":{\"title\":\"安全扫描\"}}");
        McpDispatcher.DispatchResponse response = dispatch(
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

        McpDispatcher.DispatchResponse response = dispatch(request("10", "tools/call",
                "{\"name\":\"create_document\",\"arguments\":{\"contentFormat\":\"BLOCKNOTE\",\"projectId\":7,\"title\":\"设计\","
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
    void readsRootBlocksAndVersionForLocalEditing() throws Exception {
        String content = "[{\"id\":\"h1\",\"type\":\"heading\",\"props\":{\"level\":1},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"API\"}],\"children\":["
                + "{\"id\":\"p1\",\"type\":\"paragraph\",\"content\":[],\"children\":[]}]}]";
        when(projectDocumentQueryService.getDocument(11L, 7L)).thenReturn(
                new ProjectDocumentResponse(11L, 7L, null, null, null, "设计", content, List.of(),
                        0, 19L, 7L, 7L, false, null, null, null, "BLOCKNOTE"));

        McpDispatcher.DispatchResponse response = dispatch(request("blocks", "tools/call",
                "{\"name\":\"get_document_blocks\",\"arguments\":{\"documentId\":11}}"),
                null, null, null, null, 7L);

        JsonNode result = response.body().path("result").path("structuredContent");
        assertEquals(19L, result.path("version").asLong());
        assertEquals("h1", result.path("blocks").get(0).path("id").asText());
        assertEquals("p1", result.path("blocks").get(0).path("children").get(0).path("id").asText());
    }

    @Test
    void readsSelectedBlockAsACompleteSubtree() throws Exception {
        String content = "[{\"id\":\"h1\",\"type\":\"heading\",\"props\":{\"level\":1},"
                + "\"content\":[],\"children\":[{\"id\":\"p1\",\"type\":\"paragraph\","
                + "\"content\":[],\"children\":[]}]}]";
        when(projectDocumentQueryService.getDocument(11L, 7L)).thenReturn(documentResponse(content));

        McpDispatcher.DispatchResponse response = dispatch(request("subtree", "tools/call",
                "{\"name\":\"get_document_blocks\",\"arguments\":{\"documentId\":11,\"blockId\":\"h1\"}}"),
                null, null, null, null, 7L);

        JsonNode blocks = response.body().path("result").path("structuredContent").path("blocks");
        assertEquals(1, blocks.size());
        assertEquals("h1", blocks.get(0).path("id").asText());
        assertEquals("p1", blocks.get(0).path("children").get(0).path("id").asText());
    }

    @Test
    void patchDocumentResolvesUploadedImageAndKeepsExistingBlocks() throws Exception {
        when(projectDocumentAttachmentService.uploadImage(
                eq(11L), eq("chart.png"), eq("image/png"), any(byte[].class), any(), eq(7L)))
                .thenReturn(attachment(42L, 11L));
        AtomicReference<String> patchedContent = new AtomicReference<>();
        when(documentPatchService.patch(eq(11L), eq(4L), eq("retry-1"), any(), eq(null), eq(7L), any(BiFunction.class)))
                .thenAnswer(invocation -> {
                    BiFunction<String, Long, String> factory = invocation.getArgument(6);
                    patchedContent.set(factory.apply(
                            "[{\"id\":\"keep\",\"type\":\"paragraph\",\"content\":[],\"children\":[]}]", 11L));
                    return objectMapper.createObjectNode().put("documentId", 11).put("version", 5);
                });

        McpDispatcher.DispatchResponse response = dispatch(request("patch-img", "tools/call",
                "{\"name\":\"patch_document\",\"arguments\":{\"documentId\":11,\"expectedVersion\":4,"
                        + "\"operationId\":\"retry-1\",\"operations\":[{\"op\":\"insert_block\","
                        + "\"block\":{\"type\":\"documentImage\",\"props\":{\"imageRef\":\"chart\"},"
                        + "\"content\":[],\"children\":[]}}],\"images\":[{\"ref\":\"chart\","
                        + "\"fileName\":\"chart.png\",\"contentType\":\"image/png\",\"dataBase64\":\"AQID\"}]}}"),
                null, null, null, null, 7L);

        assertEquals(200, response.status());
        JsonNode patched = objectMapper.readTree(patchedContent.get());
        assertEquals("keep", patched.get(0).path("id").asText());
        assertEquals("documentImage", patched.get(1).path("type").asText());
        assertEquals(42L, patched.get(1).path("props").path("imageAssetId").asLong());
        verify(projectDocumentAttachmentService).uploadImage(
                eq(11L), eq("chart.png"), eq("image/png"), any(byte[].class), any(), eq(7L));
    }

    @Test
    void patchOperationFailureReturnsStableBusinessErrorAndZeroBasedIndex() {
        when(documentPatchService.patch(eq(11L), eq(4L), eq("bad-op"), any(), eq(null), eq(7L), any(BiFunction.class)))
                .thenAnswer(invocation -> {
                    BiFunction<String, Long, String> factory = invocation.getArgument(6);
                    return factory.apply("[]", 11L);
                });

        McpDispatcher.DispatchResponse response = dispatch(request("patch-error", "tools/call",
                "{\"name\":\"patch_document\",\"arguments\":{\"documentId\":11,\"expectedVersion\":4,"
                        + "\"operationId\":\"bad-op\",\"operations\":[{\"op\":\"unknown\"}]}}"),
                null, null, null, null, 7L);

        assertEquals(200, response.status());
        JsonNode result = response.body().path("result");
        assertEquals(true, result.path("isError").asBoolean());
        assertEquals("PATCH_OPERATION_INVALID", result.path("structuredContent").path("errorType").asText());
        assertEquals(0, result.path("structuredContent").path("operationIndex").asInt());
    }

    @Test
    void createsDocumentWithGfmTableAsNativeBlockNoteTable() throws Exception {
        when(projectDocumentCommandService.createWithContentFactory(
                eq(7L), any(CreateProjectDocumentRequest.class), eq(7L), any(LongFunction.class)))
                .thenAnswer(invocation -> {
                    LongFunction<String> factory = invocation.getArgument(3);
                    return documentResponse(factory.apply(11L));
                });

        McpDispatcher.DispatchResponse response = dispatch(request("10b", "tools/call",
                "{\"name\":\"create_document\",\"arguments\":{\"contentFormat\":\"BLOCKNOTE\",\"projectId\":7,\"title\":\"设计\","
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

        McpDispatcher.DispatchResponse response = dispatch(request("11", "tools/call",
                "{\"name\":\"update_document\",\"arguments\":{\"contentFormat\":\"BLOCKNOTE\",\"documentId\":11,\"expectedVersion\":3,"
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

        McpDispatcher.DispatchResponse response = dispatch(request("12", "tools/call",
                "{\"name\":\"create_document\",\"arguments\":{\"contentFormat\":\"BLOCKNOTE\",\"projectId\":7,\"title\":\"设计\","
                        + "\"content\":\"![外链](https://example.com/image.png)\"}}"),
                null, null, null, null, 7L);

        assertEquals(400, response.status());
        assertEquals(-32602, response.body().path("error").path("code").asInt());
    }

    private TaskListItemResponse taskItem(Long id, String taskKey, String status, int progress, Long assigneeId) {
        TaskListItemResponse item = new TaskListItemResponse();
        item.setId(id);
        item.setTaskKey(taskKey);
        item.setTitle(taskKey);
        item.setStatus(status);
        item.setProgressPercent(progress);
        item.setAssigneeId(assigneeId);
        item.setAssigneeDisplayName(assigneeId == null ? null : "member-" + assigneeId);
        item.setUpdatedAt(LocalDateTime.of(2026, 10, 14, 16, 30));
        return item;
    }

    private TaskChangeRecord taskChange(Long id, String field, String oldValue, String newValue) {
        TaskChangeRecord change = new TaskChangeRecord();
        change.setId(id);
        change.setTaskId(id);
        change.setTaskKey("EASYGO-" + id);
        change.setField(field);
        change.setOldValue(oldValue);
        change.setNewValue(newValue);
        change.setChangedAt(LocalDateTime.of(2026, 10, 14, 12, 0));
        change.setActorId(9L);
        change.setActorDisplayName("alice");
        return change;
    }

    private ProjectDocumentResponse documentResponse(String content) {
        return new ProjectDocumentResponse(11L, 7L, null, null, null, "设计", content,
                List.of(), 0, 1L, 7L, 7L, false, null, null, null, "BLOCKNOTE");
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

    private McpDispatcher.DispatchResponse dispatch(
            String rawBody,
            String protocolVersionHeader,
            String methodHeader,
            String nameHeader,
            String origin,
            Long userId) {
        return dispatcher.dispatch(rawBody, protocolVersionHeader, methodHeader, nameHeader, origin, userId,
                Set.of("linear-lite:read", "linear-lite:write"));
    }

    @Test
    void htmlMcpCreateKeepsWhitespaceAndBypassesMarkdownAndAttachments() throws Exception {
        String html = "\n<!doctype html><html><body><script>window.x=1;</script></body></html>\n";
        when(projectDocumentCommandService.create(eq(7L), any(CreateProjectDocumentRequest.class), eq(7L)))
                .thenReturn(documentResponse(html));
        var arguments = objectMapper.createObjectNode().put("projectId", 7).put("title", "HTML")
                .put("contentFormat", "HTML").put("content", html);
        var params = objectMapper.createObjectNode().put("name", "create_document");
        params.set("arguments", arguments);
        var response = dispatch(request("html", "tools/call", params.toString()), null, null, null, null, 7L);
        assertFalse(response.body().path("result").path("isError").asBoolean());
        var captor = org.mockito.ArgumentCaptor.forClass(CreateProjectDocumentRequest.class);
        verify(projectDocumentCommandService).create(eq(7L), captor.capture(), eq(7L));
        assertEquals(html, captor.getValue().content());
        assertEquals("HTML", captor.getValue().contentFormat());
        verify(projectDocumentCommandService, never()).createWithContentFactory(anyLong(), any(), anyLong(), any());
        verifyNoInteractions(projectDocumentAttachmentService);
    }

    @Test
    void htmlMcpRejectsAttachmentInputBeforeCallingDocumentService() throws Exception {
        var params = objectMapper.readTree("{\"name\":\"create_document\",\"arguments\":{\"projectId\":7,\"title\":\"HTML\","
                + "\"contentFormat\":\"HTML\",\"content\":\"<html></html>\",\"images\":[]}}");
        var response = dispatch(request("html-images", "tools/call", params.toString()), null, null, null, null, 7L);
        assertTrue(response.body().has("error"));
        verifyNoInteractions(projectDocumentCommandService, projectDocumentAttachmentService);
    }

}
