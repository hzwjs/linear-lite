package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.TaskExecutionOperation;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskExecutionOperationServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskExecutionOperationMapper mapper = mock(TaskExecutionOperationMapper.class);
    private final TaskExecutionOperationService service = new TaskExecutionOperationService(mapper, json);

    @Test void absentOperationHasNoReplay() throws Exception {
        assertNull(service.findReplay(6L, 7L, "op", "claim", json.readTree("{\"taskId\":8}")));
    }

    @Test void replayPreservesOriginalResultAndIgnoresObjectKeyOrder() throws Exception {
        TaskExecutionOperation previous = new TaskExecutionOperation();
        previous.setKind("claim");
        previous.setRequestJson("{\"taskId\":8,\"inputRevision\":3}");
        previous.setResponseJson("{\"attemptId\":9,\"leaseUntil\":\"2026-11-10T12:00:00\"}");
        when(mapper.selectForUpdate(6L, 7L, "op")).thenReturn(previous);
        var result = service.findReplay(6L, 7L, "op", "claim",
                json.readTree("{\"inputRevision\":3,\"taskId\":8}"));
        assertEquals(json.readTree(previous.getResponseJson()), result);
        verify(mapper, never()).insert(any(TaskExecutionOperation.class));
    }

    @Test void reusedKeyWithDifferentKindOrRequestConflicts() throws Exception {
        TaskExecutionOperation previous = new TaskExecutionOperation();
        previous.setKind("checkpoint");
        previous.setRequestJson("{\"attemptId\":9,\"finding\":\"a\"}");
        previous.setResponseJson("{\"sequence\":1}");
        when(mapper.selectForUpdate(6L, 7L, "op")).thenReturn(previous);
        var same = json.readTree(previous.getRequestJson());
        var changed = json.readTree("{\"attemptId\":9,\"finding\":\"b\"}");
        assertThrows(ConflictOperationException.class, () -> service.findReplay(6L, 7L, "op", "stop", same));
        assertThrows(ConflictOperationException.class, () -> service.findReplay(6L, 7L, "op", "checkpoint", changed));
    }

    @Test void saveRetainsRequestAndOriginalResponse() throws Exception {
        var request = json.readTree("{\"attemptId\":9,\"reason\":\"user_stop\"}");
        var response = json.readTree("{\"attemptId\":9,\"status\":\"stop_requested\"}");
        when(mapper.insert(any(TaskExecutionOperation.class))).thenReturn(1);
        service.save(6L, 7L, "stop-1", "request_stop", 9L, request, response);
        var captured = ArgumentCaptor.forClass(TaskExecutionOperation.class);
        verify(mapper).insert(captured.capture());
        var row = captured.getValue();
        assertEquals(6L, row.getProjectId());
        assertEquals(7L, row.getUserId());
        assertEquals(9L, row.getAttemptId());
        assertEquals(request, json.readTree(row.getRequestJson()));
        assertEquals(response, json.readTree(row.getResponseJson()));
    }

    @Test void longIdentifiersReplayAfterDatabaseJsonRoundTrip() {
        var request = json.createObjectNode().put("taskId", 8L).put("inputRevision", 3L);
        TaskExecutionOperation previous = new TaskExecutionOperation();
        previous.setKind("claim");
        previous.setRequestJson(request.toString());
        previous.setResponseJson("{\"attemptId\":9}");
        when(mapper.selectForUpdate(6L, 7L, "op")).thenReturn(previous);
        assertEquals(9L, service.findReplay(6L, 7L, "op", "claim", request).path("attemptId").longValue());
    }

    @Test void rejectsInvalidOperationKeyAndFailedInsert() throws Exception {
        var request = json.readTree("{}");
        assertThrows(IllegalArgumentException.class, () -> service.findReplay(6L, 7L, " ", "claim", request));
        assertThrows(IllegalArgumentException.class, () -> service.findReplay(6L, 7L, "x".repeat(129), "claim", request));
        assertThrows(IllegalStateException.class, () -> service.save(6L, 7L, "op", "claim", 9L, request, request));
    }
}
