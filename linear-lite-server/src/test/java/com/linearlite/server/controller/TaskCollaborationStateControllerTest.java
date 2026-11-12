package com.linearlite.server.controller;

import com.linearlite.server.dto.TaskCollaborationState;
import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.TaskCollaborationStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskCollaborationStateControllerTest {
    private final TaskCollaborationStateService service = mock(TaskCollaborationStateService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TaskCollaborationStateController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void authenticatedUserAndTaskKeyAreTheReadContext() throws Exception {
        when(service.get(eq(7L), any())).thenReturn(new TaskCollaborationState(
                Instant.parse("2026-11-10T08:00:00Z"), 4L, "todo", 0, null, null, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        mvc.perform(post("/api/collaboration/tasks/state").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"taskKey\":\"TEST-1\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Linear-Lite-Server-Time", "2026-11-10T08:00:00Z"))
                .andExpect(jsonPath("$.data.inputRevision").value(4))
                .andExpect(jsonPath("$.data.status").value("todo"))
                .andExpect(jsonPath("$.data.progressPercent").value(0));
        var request = ArgumentCaptor.forClass(TaskCollaborationState.Request.class);
        verify(service).get(eq(7L), request.capture());
        assertEquals(new TaskCollaborationState.Request("TEST-1"), request.getValue());
    }

    @Test void malformedJsonDoesNotReachTheService() throws Exception {
        mvc.perform(post("/api/collaboration/tasks/state").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
