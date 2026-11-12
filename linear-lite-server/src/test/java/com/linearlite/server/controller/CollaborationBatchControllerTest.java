package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationBatchDtos.StartRequest;
import com.linearlite.server.dto.CollaborationBatchDtos.AuthorizeGoalRequest;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.CollaborationBatchControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollaborationBatchControllerTest {
    private final CollaborationBatchControlService service = mock(CollaborationBatchControlService.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CollaborationBatchController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void startUsesAuthenticatedUserAndReturnsNoStore() throws Exception {
        when(service.start(eq(7L), any())).thenReturn(json.createObjectNode().put("batchId", "b-1"));
        mvc.perform(post("/api/collaboration/batches/start")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":6,"operationId":"op-1","bindingId":10,
                                 "bindingRevision":2,"taskIds":[101],"maxDurationMs":60000}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.batchId").value("b-1"));
        var input = ArgumentCaptor.forClass(StartRequest.class);
        verify(service).start(eq(7L), input.capture());
        assertEquals(6L, input.getValue().projectId());
    }

    @Test void malformedAndMissingPayloadsAreRejected() throws Exception {
        mvc.perform(post("/api/collaboration/batches/stop")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/collaboration/batches/get")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void goalAuthorizationUsesTheAuthenticatedUserAndFactIdentifier() throws Exception {
        when(service.authorizeGoal(eq(7L), any())).thenReturn(json.createObjectNode().put("batchId", "new-batch"));
        mvc.perform(post("/api/collaboration/batches/authorize-goal")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":6,\"batchId\":\"b-1\",\"requestOperationId\":\"request-1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.batchId").value("new-batch"));
        var input = ArgumentCaptor.forClass(AuthorizeGoalRequest.class);
        verify(service).authorizeGoal(eq(7L), input.capture());
        assertEquals(new AuthorizeGoalRequest(6L, "b-1", "request-1"), input.getValue());
        mvc.perform(post("/api/collaboration/batches/authorize-goal")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":6,\"batchId\":\"b-1\",\"requestOperationId\":\"request-1\",\"userId\":99}"))
                .andExpect(status().isBadRequest());
        verify(service, times(1)).authorizeGoal(anyLong(), any());
    }

    @Test void missingFieldsReachDomainValidationWithoutFallingBackToAnotherKey() throws Exception {
        when(service.start(eq(7L), any())).thenThrow(new IllegalArgumentException("项目标识无效"));
        mvc.perform(post("/api/collaboration/batches/start")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project_id\":6,\"operationId\":\"op-1\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void rejectsLegacyBudgetAndUserIdentityFields() throws Exception {
        for (String extra : new String[]{"\"maxSlots\":2", "\"maxAttempts\":5", "\"userId\":99"}) {
            mvc.perform(post("/api/collaboration/batches/start")
                            .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":6,\"operationId\":\"op\",\"bindingId\":10,\"bindingRevision\":2,\"taskIds\":[101],\"maxDurationMs\":60000," + extra + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

}
