package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.AgentTaskEventBatchRequest;
import com.linearlite.server.dto.AgentTaskEventRequest;
import com.linearlite.server.entity.AgentTaskJob;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTaskEventServiceTest {
    private final AgentTaskSessionMapper sessionMapper = mock(AgentTaskSessionMapper.class);
    private final AgentTaskOrchestrationService orchestrationService = mock(AgentTaskOrchestrationService.class);
    private final TaskPermissionGuard taskPermissionGuard = mock(TaskPermissionGuard.class);
    private final AgentTaskEventSseBroadcaster broadcaster = mock(AgentTaskEventSseBroadcaster.class);
    private final AgentTaskEventService service = new AgentTaskEventService(
            sessionMapper, orchestrationService, taskPermissionGuard, broadcaster, new ObjectMapper());

    @Test
    void rejectsNonIncreasingSequenceBeforeBroadcasting() {
        AgentTaskEventBatchRequest request = batch("exec-1",
                event(2L, "progress", "第二步"), event(1L, "progress", "第一步"));

        assertThrows(RuntimeException.class, () -> service.report(7L, 9L, request));
        verifyNoInteractions(orchestrationService, broadcaster);
    }

    @Test
    void acceptsOrderedEventsAndPublishesWithoutPersistence() {
        when(orchestrationService.requireOwnedJob(7L, 9L, "exec-1")).thenReturn(job(9L));

        service.report(7L, 9L, batch("exec-1",
                event(1L, "started", "Pi 已开始"), event(2L, "tool_call", "正在查询天气")));

        verify(broadcaster).send(any(String.class), anyList());
        verify(orchestrationService).markJobRunning(any(AgentTaskJob.class));
    }

    @Test
    void streamChecksUserTaskPermissionAndExecutionOwnership() {
        Task task = new Task();
        task.setId(10L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-84", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setExecutionId("exec-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        service.requireTaskExecution("LINEAR-LITE-84", 7L, "exec-1");
        verify(taskPermissionGuard).requireTaskAccessByKey("LINEAR-LITE-84", 7L);
    }

    private static AgentTaskJob job(Long id) {
        AgentTaskJob job = new AgentTaskJob();
        job.setId(id);
        job.setSessionId(1L);
        job.setExecutionId("exec-1");
        job.setStatus("leased");
        return job;
    }

    private static AgentTaskEventRequest event(Long sequence, String type, String summary) {
        AgentTaskEventRequest event = new AgentTaskEventRequest();
        event.setSequenceNo(sequence);
        event.setEventType(type);
        event.setSummary(summary);
        event.setPayload(new ObjectMapper().createObjectNode());
        return event;
    }

    private static AgentTaskEventBatchRequest batch(String executionId, AgentTaskEventRequest... events) {
        AgentTaskEventBatchRequest request = new AgentTaskEventBatchRequest();
        request.setExecutionId(executionId);
        request.setEvents(List.of(events));
        return request;
    }
}
