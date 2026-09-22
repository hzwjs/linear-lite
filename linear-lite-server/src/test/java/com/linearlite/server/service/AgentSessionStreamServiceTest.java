package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.AgentSessionSnapshot;
import com.linearlite.server.dto.AgentSessionSnapshotClaimResponse;
import com.linearlite.server.dto.RuntimeDisplayBlockBatchRequest;
import com.linearlite.server.dto.RuntimeDisplayBlockRequest;
import com.linearlite.server.dto.SessionDisplayBlock;
import com.linearlite.server.entity.AgentTaskJob;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentSessionStreamServiceTest {
    private final AgentTaskSessionMapper sessionMapper = mock(AgentTaskSessionMapper.class);
    private final AgentTaskOrchestrationService orchestrationService = mock(AgentTaskOrchestrationService.class);
    private final TaskPermissionGuard taskPermissionGuard = mock(TaskPermissionGuard.class);
    private final ExecutionCredentialService credentialService = mock(ExecutionCredentialService.class);
    private final AgentSessionSseBroadcaster broadcaster = mock(AgentSessionSseBroadcaster.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentSessionStreamService service = new AgentSessionStreamService(
            sessionMapper, orchestrationService, taskPermissionGuard, credentialService, broadcaster);

    @BeforeEach
    void setUpSession() {
        Task task = new Task();
        task.setId(10L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-102", 7L)).thenReturn(task);
        when(sessionMapper.selectOne(any())).thenReturn(session());
    }

    @Test
    void rejectsSnapshotRequestWhenBridgeIsOfflineWithoutServingOldContent() {
        when(credentialService.isOnline("exec-1")).thenReturn(false);

        assertThrows(ConflictOperationException.class,
                () -> service.requestSnapshot("LINEAR-LITE-102", 7L, "exec-1"));

        assertEquals(0, service.pendingRequestCount());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void schedulesClaimAndForwardsOneSessionSnapshotWithoutPersistence() {
        when(credentialService.isOnline("exec-1")).thenReturn(true);
        String requestId = service.requestSnapshot("LINEAR-LITE-102", 7L, "exec-1");

        AgentSessionSnapshotClaimResponse claim = service.claimSnapshot(7L, "exec-1");
        assertEquals(requestId, claim.requestId());
        assertEquals("pi-session-1", claim.piSessionId());
        assertEquals(30L, claim.projectId());

        var tool = mapper.createObjectNode();
        tool.put("name", "bash");
        tool.set("arguments", mapper.createObjectNode().put("command", "pwd"));
        tool.putArray("content").add(mapper.createObjectNode().put("type", "text").put("text", "/workspace"));
        tool.put("isError", false);
        tool.putNull("truncation");
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
                "exec-1", "pi-session-1", "entry-1",
                List.of(
                        new SessionDisplayBlock(
                                "entry-user:0", "entry-user", null, 1L, "user", "final",
                                mapper.createObjectNode().put("text", "review code").put("thinking", ""),
                                null, "2026-08-18T00:00:00Z"),
                        new SessionDisplayBlock(
                                "entry-assistant:0", "entry-assistant", "assistant:1000", 2L,
                                "assistant", "final",
                                mapper.createObjectNode().put("text", "checking").put("thinking", ""),
                                null, "2026-08-18T00:00:01Z"),
                        new SessionDisplayBlock(
                                "call-1", "entry-assistant", "call-1", 3L, "tool", "final",
                                null, tool, "2026-08-18T00:00:01Z")));
        service.completeSnapshot(7L, requestId, snapshot);

        verify(broadcaster).sendSnapshot(snapshot);
        assertEquals(0, service.pendingRequestCount());
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).updateById(any());
    }

    @Test
    void rejectsSnapshotWhenRuntimeIdentityDoesNotMatchBlockKind() {
        when(credentialService.isOnline("exec-1")).thenReturn(true);
        String requestId = service.requestSnapshot("LINEAR-LITE-102", 7L, "exec-1");

        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
                "exec-1", "pi-session-1", "entry-1",
                List.of(new SessionDisplayBlock(
                        "entry-1:0", "entry-1", null, 1L, "assistant", "final",
                        mapper.createObjectNode().put("text", "missing identity").put("thinking", ""),
                        null, "2026-08-18T00:00:00Z")));

        assertThrows(IllegalArgumentException.class,
                () -> service.completeSnapshot(7L, requestId, snapshot));
        verifyNoInteractions(broadcaster);
    }

    @Test
    void validatesAndForwardsRuntimeBlocksWithoutBuildingHistory() {
        AgentTaskJob job = new AgentTaskJob();
        job.setId(9L);
        when(orchestrationService.requireOwnedJob(7L, 9L, "exec-1")).thenReturn(job);
        RuntimeDisplayBlockRequest block = new RuntimeDisplayBlockRequest();
        block.setBlockId("assistant:1");
        block.setRevision(2L);
        block.setKind("assistant");
        block.setPhase("streaming");
        block.setContent(mapper.createObjectNode().put("text", "streaming").put("thinking", ""));
        RuntimeDisplayBlockBatchRequest batch = new RuntimeDisplayBlockBatchRequest();
        batch.setExecutionId("exec-1");
        batch.setBlocks(List.of(block));

        service.reportRuntime(7L, 9L, batch);

        verify(orchestrationService).markJobRunning(job);
        verify(broadcaster).sendRuntime(argThat(runtime ->
                runtime.blockId().equals("assistant:1") && runtime.revision() == 2L));
        assertTrue(service.pendingRequestCount() == 0);
    }

    private static AgentTaskSession session() {
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setExecutionId("exec-1");
        session.setTaskId(10L);
        session.setProjectId(30L);
        session.setAgentUserId(7L);
        session.setSessionId("pi-session-1");
        return session;
    }
}
