package com.linearlite.server.service;

import com.linearlite.server.dto.PiModelDescriptor;
import com.linearlite.server.dto.PiSettingsCurrent;
import com.linearlite.server.dto.PiSettingsRequest;
import com.linearlite.server.dto.PiSettingsRequestClaimResponse;
import com.linearlite.server.dto.PiSettingsState;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PiSettingsRequestServiceTest {
    private final AgentTaskSessionMapper sessionMapper = mock(AgentTaskSessionMapper.class);
    private final TaskPermissionGuard taskPermissionGuard = mock(TaskPermissionGuard.class);
    private final ExecutionCredentialService credentialService = mock(ExecutionCredentialService.class);
    private final AgentSessionSseBroadcaster broadcaster = mock(AgentSessionSseBroadcaster.class);
    private final PiSettingsRequestService service = new PiSettingsRequestService(
            sessionMapper, taskPermissionGuard, credentialService, broadcaster);

    @BeforeEach
    void setUpSession() {
        Task task = new Task();
        task.setId(10L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-102", 7L)).thenReturn(task);
        when(sessionMapper.selectOne(any())).thenReturn(session());
        when(credentialService.isOnline("exec-1")).thenReturn(true);
    }

    @Test
    void schedulesReadAndForwardsOnlyBridgeReturnedState() {
        PiSettingsRequest request = new PiSettingsRequest();
        request.setAction("read");
        String requestId = service.request("LINEAR-LITE-102", 7L, "exec-1", request);

        PiSettingsRequestClaimResponse claim = service.claim(7L, "exec-1");
        assertEquals(requestId, claim.requestId());
        assertEquals("read", claim.action());
        assertEquals("pi-session-1", claim.piSessionId());

        PiSettingsState state = state();
        service.complete(7L, requestId, state);

        verify(broadcaster).sendSettingsState(state);
        assertEquals(0, service.pendingRequestCount());
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).updateById(any());
    }

    @Test
    void rejectsModelSwitchWhenSessionIsNotWaitingForInput() {
        AgentTaskSession running = session();
        running.setStatus("running");
        when(sessionMapper.selectOne(any())).thenReturn(running);
        PiSettingsRequest request = new PiSettingsRequest();
        request.setAction("set_model");
        request.setProvider("anthropic");
        request.setModelId("claude-sonnet");

        assertThrows(ConflictOperationException.class,
                () -> service.request("LINEAR-LITE-102", 7L, "exec-1", request));
        assertEquals(0, service.pendingRequestCount());
    }

    @Test
    void rejectsMixedSettingsPayloadInsteadOfChoosingAFallbackField() {
        PiSettingsRequest request = new PiSettingsRequest();
        request.setAction("set_model");
        request.setProvider("anthropic");
        request.setModelId("claude-sonnet");
        request.setLevel("high");

        assertThrows(IllegalArgumentException.class,
                () -> service.request("LINEAR-LITE-102", 7L, "exec-1", request));
        assertEquals(0, service.pendingRequestCount());
    }

    private static PiSettingsState state() {
        PiModelDescriptor model = new PiModelDescriptor("anthropic", "claude-sonnet", "Claude Sonnet");
        return new PiSettingsState("exec-1", new PiSettingsCurrent(model, "high"), List.of(model), List.of("low", "high"));
    }

    private static AgentTaskSession session() {
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setExecutionId("exec-1");
        session.setTaskId(10L);
        session.setProjectId(30L);
        session.setAgentUserId(7L);
        session.setSessionId("pi-session-1");
        session.setStatus("waiting_input");
        return session;
    }
}
