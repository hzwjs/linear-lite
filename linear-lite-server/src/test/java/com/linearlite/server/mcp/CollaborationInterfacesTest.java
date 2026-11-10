package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.config.McpOAuthProperties;
import com.linearlite.server.config.McpProperties;
import com.linearlite.server.controller.TaskCollaborationController;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.ProjectDeviceBindingResponse;
import com.linearlite.server.dto.TaskCollaborationInputs;
import com.linearlite.server.dto.TaskDeliveryEvidence;
import com.linearlite.server.dto.TaskExecutionCheckpoint;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.mapper.UserMapper;
import com.linearlite.server.service.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class CollaborationInterfacesTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ProjectDeviceBindingService bindings = mock(ProjectDeviceBindingService.class);
    private final CollaborationBatchAuthorizationService batches = mock(CollaborationBatchAuthorizationService.class);
    private final TaskCollaborationInputService inputs = mock(TaskCollaborationInputService.class);
    private final TaskCollaborationClaimService claims = mock(TaskCollaborationClaimService.class);
    private final TaskExecutionLifecycleService lifecycle = mock(TaskExecutionLifecycleService.class);
    private final TaskCollaborationCompletionService completion = mock(TaskCollaborationCompletionService.class);
    private final UnacknowledgedClaimRecoveryService unacknowledged = mock(UnacknowledgedClaimRecoveryService.class);
    private CollaborationApiService api;
    private McpDispatcher dispatcher;
    private MockMvc rest;

    private static final String BATCH = "{\"batchToken\":\"fixture-batch\",\"bindingId\":2,\"deviceId\":\"dev\",\"projectDirectory\":\"/p\"}";
    private static final String COMMAND = "{\"attemptId\":3,\"claimToken\":\"fixture-claim\",\"inputRevision\":1,\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"operationId\":\"op \"}";
    private static final String RECOVERY = "{\"attemptId\":3,\"claimToken\":\"fixture-claim\",\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"operationId\":\"stop\"}";

    @BeforeEach void setup() {
        api = new CollaborationApiService(json, bindings, batches, inputs, claims, lifecycle, completion, unacknowledged);
        McpToolRegistry registry = new McpToolRegistry(json, new MarkdownToBlockNoteConverter(json),
                mock(McpDocumentImageContentService.class), mock(ProjectService.class), mock(TaskCommandService.class),
                mock(TaskQueryService.class), mock(TaskActivityService.class), mock(TaskAttachmentService.class),
                mock(TaskCommentService.class), mock(McpTaskCommentImageContentService.class),
                mock(ProjectDocumentCommandService.class), mock(ProjectDocumentQueryService.class),
                mock(McpDocumentPatchService.class), mock(UserMapper.class), api);
        dispatcher = new McpDispatcher(json, new McpProperties(), new McpOAuthProperties(), registry);
        rest = MockMvcBuilders.standaloneSetup(new TaskCollaborationController(api))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void catalogDiscoversAllOperationsWithSchemasAndObservationRequiresWriteScope() {
        JsonNode tools = dispatch("tools/list", json.createObjectNode(), Set.of("linear-lite:read"))
                .body().path("result").path("tools");
        assertEquals(35, tools.size());
        for (var definition : api.definitions()) {
            JsonNode tool = findTool(tools, definition.name());
            assertNotNull(tool, definition.name());
            assertEquals("object", tool.path("inputSchema").path("type").asText());
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
            assertTrue(tool.path("inputSchema").path("required").size() > 0);
        }
        JsonNode observe = findTool(tools, "observe_collaboration_environment");
        assertEquals("linear-lite:write", observe.path("securitySchemes").get(0).path("scopes").get(0).asText());
        assertFalse(observe.path("annotations").path("readOnlyHint").asBoolean());
        var denied = callMcp("observe_collaboration_environment", parse("{\"batch\":" + BATCH + ",\"available\":[]}"), Set.of("linear-lite:read"));
        assertTrue(denied.body().path("result").path("isError").asBoolean());
        verifyNoInteractions(claims);
    }

    @Test void everyOperationUsesSameResultAndAuthenticatedUserThroughBothEntrances() throws Exception {
        Task task = new Task(); task.setId(5L);
        var bind = new ProjectDeviceBindingResponse(2L, 7L, 6L, "dev", "/p", 1L, null, null);
        when(bindings.save(7L, 6L, "dev", "/p")).thenReturn(bind);
        when(bindings.get(7L, 6L, "dev")).thenReturn(bind);
        when(bindings.list(7L, 6L)).thenReturn(List.of(bind));
        when(batches.issue(eq(7L), eq(6L), eq(2L), eq("dev"), eq("/p"), anyList(), any(Instant.class)))
                .thenReturn(new CollaborationBatchAuthorizationService.IssuedBatch("batch-id", "fixture-issued", Instant.parse("2099-01-01T00:00:00Z")));
        when(inputs.getInputs("LL-1", 7L)).thenReturn(new TaskCollaborationInputs(1L, true, List.of("src/"), List.of(), List.of()));
        when(inputs.replaceInputs(eq("LL-1"), any(TaskCollaborationInputs.class), eq(7L))).thenReturn(task);
        when(claims.observe(eq(7L), any(CollaborationBatchRequest.class), anyList()))
                .thenReturn(new TaskCollaborationClaimService.Observation("READY", List.of(), List.of()));
        when(claims.claim(eq(7L), any(CollaborationBatchRequest.class), eq(5L), eq(1L), eq("host"), eq("agent"), eq("op "), anyList()))
                .thenReturn(new TaskCollaborationClaimService.ClaimResult(true, null, 3L, "fixture-claim", 1L, Instant.parse("2099-01-01T00:00:00Z")));
        when(claims.expand(eq(7L), any(CollaborationBatchRequest.class), eq(5L), any(CollaborationAttemptCommand.class), anyList()))
                .thenReturn(new TaskCollaborationClaimService.ScopeResult(false, "范围冲突", List.of("src/")));
        var state = new TaskExecutionLifecycleService.Result(false, "租约过期", json.createObjectNode().put("accepted", false).put("reason", "租约过期"));
        when(lifecycle.renew(eq(7L), any(CollaborationBatchRequest.class), eq(5L), any(CollaborationAttemptCommand.class))).thenReturn(state);
        when(lifecycle.checkpoint(eq(7L), any(CollaborationBatchRequest.class), eq(5L), any(CollaborationAttemptCommand.class), any(TaskExecutionCheckpoint.class))).thenReturn(state);
        when(lifecycle.requestStop(eq(7L), eq(6L), eq(2L), eq("dev"), eq(5L), any(CollaborationAttemptCommand.class), eq("暂停"))).thenReturn(state);
        when(lifecycle.confirmStop(eq(7L), eq(6L), eq(2L), eq("dev"), eq(5L), any(CollaborationAttemptCommand.class), any(CollaborationStopConfirmation.class))).thenReturn(state);
        var recoveryAttempt = new com.linearlite.server.entity.TaskExecutionAttempt();
        recoveryAttempt.setId(3L); recoveryAttempt.setTaskId(5L); recoveryAttempt.setBindingId(2L);
        recoveryAttempt.setHeldWriteScopes(List.of("src/"));
        when(lifecycle.readRecovery(eq(7L), eq(6L), eq(2L), eq("dev"), eq(5L), any(CollaborationAttemptCommand.class)))
                .thenReturn(com.linearlite.server.dto.TaskExecutionAttemptResponse.from(recoveryAttempt));
        when(unacknowledged.recover(eq(7L), eq("fixture-batch"), eq(2L), eq("dev"), eq(5L), eq("host"), eq("agent"),
                eq("claim-original"), eq("recover-op"), any(CollaborationStopConfirmation.class))).thenReturn(state);
        when(completion.complete(eq(7L), any(CollaborationBatchRequest.class), eq(5L), any(CollaborationAttemptCommand.class),
                any(TaskDeliveryEvidence.class), any(CollaborationStopConfirmation.class)))
                .thenReturn(new TaskCollaborationCompletionService.Result(false, "输入变化", json.createObjectNode().put("accepted", false)));

        String input = "{\"inputRevision\":1,\"agentExecutionEnabled\":true,\"writeScopes\":[\"src/\"],\"publicContextRefs\":[],\"prerequisiteTaskIds\":[]}";
        String confirmation = "{\"stopped\":true,\"detail\":\"进程结束\"}";
        String evidence = "{\"conditions\":[{\"condition\":\"完成\",\"result\":\"通过\",\"evidenceRefs\":[\"test\"]}],\"changedPaths\":[],\"artifactRefs\":[\"result\"],\"projectCheckpoint\":\"point\"}";
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("save_project_device_binding", new Case("/bindings/save", "{\"projectId\":6,\"deviceId\":\"dev\",\"projectDirectory\":\"/p\"}"));
        cases.put("get_project_device_binding", new Case("/bindings/get", "{\"projectId\":6,\"deviceId\":\"dev\"}"));
        cases.put("list_project_device_bindings", new Case("/bindings/list", "{\"projectId\":6}"));
        cases.put("delete_project_device_binding", new Case("/bindings/delete", "{\"projectId\":6,\"deviceId\":\"dev\"}"));
        cases.put("issue_collaboration_batch", new Case("/batches/issue", "{\"projectId\":6,\"bindingId\":2,\"deviceId\":\"dev\",\"projectDirectory\":\"/p\",\"taskIds\":[5],\"expiresAt\":\"2099-01-01T00:00:00Z\"}"));
        cases.put("replace_task_collaboration_inputs", new Case("/inputs/replace", "{\"taskKey\":\"LL-1\",\"inputs\":" + input + "}"));
        cases.put("get_task_collaboration_inputs", new Case("/inputs/get", "{\"taskKey\":\"LL-1\"}"));
        cases.put("observe_collaboration_environment", new Case("/environment/observe", "{\"batch\":" + BATCH + ",\"available\":[]}"));
        cases.put("claim_collaboration_task", new Case("/tasks/claim", "{\"batch\":" + BATCH + ",\"taskId\":5,\"inputRevision\":1,\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"operationId\":\"op \",\"available\":[]}"));
        cases.put("expand_collaboration_write_scopes", new Case("/attempts/expand-scopes", "{\"batch\":" + BATCH + ",\"taskId\":5,\"command\":" + COMMAND + ",\"requestedScopes\":[\"docs/\"]}"));
        cases.put("renew_collaboration_attempt", new Case("/attempts/renew", "{\"batch\":" + BATCH + ",\"taskId\":5,\"command\":" + COMMAND + "}"));
        cases.put("append_collaboration_checkpoint", new Case("/attempts/checkpoint", "{\"batch\":" + BATCH + ",\"taskId\":5,\"command\":" + COMMAND + ",\"checkpoint\":{\"occurredAt\":\"2026-11-10T12:00:00\",\"finding\":\"发现\",\"artifactRefs\":[]}}"));
        cases.put("complete_collaboration_task", new Case("/attempts/complete", "{\"batch\":" + BATCH + ",\"taskId\":5,\"command\":" + COMMAND + ",\"evidence\":" + evidence + ",\"confirmation\":" + confirmation + "}"));
        cases.put("request_collaboration_stop", new Case("/attempts/request-stop", "{\"projectId\":6,\"bindingId\":2,\"deviceId\":\"dev\",\"taskId\":5,\"command\":" + RECOVERY + ",\"reason\":\"暂停\"}"));
        cases.put("confirm_collaboration_stop", new Case("/attempts/confirm-stop", "{\"projectId\":6,\"bindingId\":2,\"deviceId\":\"dev\",\"taskId\":5,\"command\":" + RECOVERY + ",\"confirmation\":" + confirmation + "}"));
        cases.put("get_collaboration_recovery", new Case("/attempts/recovery", "{\"projectId\":6,\"bindingId\":2,\"deviceId\":\"dev\",\"taskId\":5,\"command\":" + RECOVERY + "}"));
        cases.put("recover_unacknowledged_claim", new Case("/attempts/recover-claim", "{\"batchToken\":\"fixture-batch\",\"bindingId\":2,\"deviceId\":\"dev\",\"taskId\":5,\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"claimOperationId\":\"claim-original\",\"operationId\":\"recover-op\",\"confirmation\":" + confirmation + "}"));

        assertEquals(17, cases.size());
        for (var entry : cases.entrySet()) {
            Case testCase = entry.getValue();
            var response = rest.perform(post("/api/collaboration" + testCase.path()).requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                    .contentType(MediaType.APPLICATION_JSON).content(testCase.body())).andReturn().getResponse();
            assertEquals(200, response.getStatus(), entry.getKey());
            assertNotNull(response.getHeader("X-Linear-Lite-Server-Time"));
            assertEquals("no-store", response.getHeader("Cache-Control"));
            JsonNode restData = json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
            var mcp = callMcp(entry.getKey(), parse(testCase.body()), Set.of("linear-lite:read", "linear-lite:write"));
            assertEquals(restData, json.readTree(mcp.body().path("result").path("structuredContent").toString()), entry.getKey());
        }
        verify(claims, times(2)).claim(eq(7L), any(CollaborationBatchRequest.class), eq(5L), eq(1L), eq("host"), eq("agent"), eq("op "), anyList());
        var captured = org.mockito.ArgumentCaptor.forClass(TaskExecutionCheckpoint.class);
        verify(lifecycle, times(2)).checkpoint(eq(7L), any(CollaborationBatchRequest.class), eq(5L), any(CollaborationAttemptCommand.class), captured.capture());
        assertEquals("op ", captured.getValue().operationId());
        assertEquals("agent", captured.getValue().agentInstanceId());
    }

    @Test void unknownFieldsAndWrongNestedTypesReturnFixedErrorsWithoutEcho() throws Exception {
        String value = "{\"batch\":" + BATCH + ",\"available\":[],\"fixture-secret-field\":1}";
        var response = rest.perform(post("/api/collaboration/environment/observe").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                .contentType(MediaType.APPLICATION_JSON).content(value)).andReturn().getResponse();
        var mcp = callMcp("observe_collaboration_environment", parse(value), Set.of("linear-lite:write"));
        assertEquals(400, response.getStatus());
        assertEquals("协作参数包含未知字段", json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("message").asText());
        assertEquals(-32602, mcp.body().path("error").path("code").asInt());
        assertFalse(response.getContentAsString().contains("fixture-secret-field"));
        assertFalse(mcp.body().toString().contains("fixture-secret-field"));
        String wrong = "{\"batch\":" + BATCH + ",\"taskId\":5,\"command\":{" + "\"attemptId\":3,\"claimToken\":\"fixture\",\"inputRevision\":\"1\",\"hostInstanceId\":\"host\",\"agentInstanceId\":\"agent\",\"operationId\":\"op\"}}";
        var invalid = callMcp("renew_collaboration_attempt", parse(wrong), Set.of("linear-lite:write"));
        var wrongRest = rest.perform(post("/api/collaboration/attempts/renew").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                .contentType(MediaType.APPLICATION_JSON).content(wrong)).andReturn().getResponse();
        assertEquals(-32602, invalid.body().path("error").path("code").asInt());
        assertEquals(400, wrongRest.getStatus());
        assertEquals(json.readTree(wrongRest.getContentAsString(StandardCharsets.UTF_8)).path("message").asText(),
                invalid.body().path("error").path("message").asText());
        verifyNoInteractions(claims, lifecycle);
    }

    @Test void clientCannotSupplyUserIdentityAndAnonymousRequestsAreRejected() throws Exception {
        String forged = "{\"projectId\":6,\"deviceId\":\"dev\",\"userId\":8}";
        var response = rest.perform(post("/api/collaboration/bindings/get").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                .contentType(MediaType.APPLICATION_JSON).content(forged)).andReturn().getResponse();
        var mcp = callMcp("get_project_device_binding", parse(forged), Set.of("linear-lite:read"));
        assertEquals(400, response.getStatus());
        assertEquals(-32602, mcp.body().path("error").path("code").asInt());

        var anonymousRest = rest.perform(post("/api/collaboration/bindings/get").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":6,\"deviceId\":\"dev\"}")).andReturn().getResponse();
        ObjectNode params = json.createObjectNode().put("name", "get_project_device_binding");
        params.set("arguments", parse("{\"projectId\":6,\"deviceId\":\"dev\"}"));
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", "1").put("method", "tools/call");
        request.set("params", params);
        var anonymousMcp = dispatcher.dispatch(request.toString(), null, null, null, null, null, Set.of("linear-lite:read"));
        assertEquals(401, anonymousRest.getStatus());
        assertEquals(401, anonymousMcp.status());
        verifyNoInteractions(bindings);
    }

    @Test void malformedJsonReturnsFixedRestErrorWithoutInput() throws Exception {
        var response = rest.perform(post("/api/collaboration/environment/observe").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                .contentType(MediaType.APPLICATION_JSON).content("{\"batchToken\":\"fixture-secret\"")).andReturn().getResponse();
        assertEquals(400, response.getStatus());
        assertEquals("协作参数JSON无效", json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("message").asText());
        assertFalse(response.getContentAsString().contains("fixture-secret"));
    }

    @Test void replayedLeaseKeepsOriginalExpiryWithFreshServerClockMetadata() throws Exception {
        var original = json.createObjectNode().put("accepted", true).put("leaseUntil", "2000-01-01T00:00:00Z");
        when(lifecycle.renew(eq(7L), any(CollaborationBatchRequest.class), eq(5L), any(CollaborationAttemptCommand.class)))
                .thenReturn(new TaskExecutionLifecycleService.Result(true, null, original));
        String body = "{\"batch\":" + BATCH + ",\"taskId\":5,\"command\":" + COMMAND + "}";
        for (int i = 0; i < 2; i++) {
            var response = rest.perform(post("/api/collaboration/attempts/renew")
                    .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertEquals(original, json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data").path("data"));
            assertTrue(Instant.parse(response.getHeader("X-Linear-Lite-Server-Time"))
                    .isAfter(Instant.parse("2000-01-01T00:00:00Z")));
        }
    }

    @Test void domainForbiddenHasSameReasonAtBothEntrances() throws Exception {
        when(claims.observe(eq(7L), any(CollaborationBatchRequest.class), anyList()))
                .thenThrow(new ForbiddenOperationException("批次授权与请求范围不匹配"));
        String value = "{\"batch\":" + BATCH + ",\"available\":[]}";
        var response = rest.perform(post("/api/collaboration/environment/observe").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                .contentType(MediaType.APPLICATION_JSON).content(value)).andReturn().getResponse();
        var mcp = callMcp("observe_collaboration_environment", parse(value), Set.of("linear-lite:write"));
        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", mcp.body().path("result").path("structuredContent").path("errorType").asText());
        assertEquals(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("message").asText(),
                mcp.body().path("result").path("structuredContent").path("message").asText());
    }

    private JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) if (name.equals(tool.path("name").asText())) return tool;
        return null;
    }

    private JsonNode parse(String value) { try { return json.readTree(value); } catch (Exception e) { throw new AssertionError(e); } }
    private McpDispatcher.DispatchResponse callMcp(String name, JsonNode arguments, Set<String> scopes) {
        ObjectNode params = json.createObjectNode().put("name", name); params.set("arguments", arguments);
        return dispatch("tools/call", params, scopes);
    }
    private McpDispatcher.DispatchResponse dispatch(String method, JsonNode params, Set<String> scopes) {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", "1").put("method", method);
        request.set("params", params);
        return dispatcher.dispatch(request.toString(), null, null, null, null, 7L, scopes);
    }
    private record Case(String path, String body) { }
}
