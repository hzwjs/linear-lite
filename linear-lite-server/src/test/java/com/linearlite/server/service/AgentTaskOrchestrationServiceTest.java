package com.linearlite.server.service;

import com.linearlite.server.dto.AgentJobClaimResponse;
import com.linearlite.server.dto.AgentSessionStateRequest;
import com.linearlite.server.dto.AgentTaskStatusResponse;
import com.linearlite.server.entity.AgentTaskJob;
import com.linearlite.server.entity.AgentTaskSession;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.AgentTaskJobMapper;
import com.linearlite.server.mapper.AgentTaskSessionMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskOrchestrationServiceTest {
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final AgentTaskSessionMapper sessionMapper = mock(AgentTaskSessionMapper.class);
    private final AgentTaskJobMapper jobMapper = mock(AgentTaskJobMapper.class);
    private final TaskPermissionGuard taskPermissionGuard = mock(TaskPermissionGuard.class);
    private final AgentTaskOrchestrationService service = new AgentTaskOrchestrationService(
            taskMapper, projectMapper, projectMemberMapper, sessionMapper, jobMapper, taskPermissionGuard);

    @Test
    void preparesCompletedTaskByResumingItsExistingPiSession() {
        Task task = new Task();
        task.setId(10L);
        task.setTaskKey("LINEAR-LITE-101");
        task.setProjectId(6L);
        task.setAssigneeId(7L);
        task.setStatus("done");
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-101", 7L)).thenReturn(task);

        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setExecutionId("exec-existing");
        session.setStatus("waiting_input");
        session.setAgentUserId(7L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.existsTurn(20L)).thenReturn(true);

        AgentTaskStatusResponse status = service.prepare(7L, "LINEAR-LITE-101");

        assertEquals("exec-existing", status.executionId());
        assertEquals("waiting_input", status.sessionStatus());
        assertEquals(true, status.hasSubmittedTurn());
        assertEquals("done", task.getStatus());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void marksANewExecutionAsHavingNoSubmittedTurn() {
        Task task = new Task();
        task.setId(10L);
        task.setTaskKey("LINEAR-LITE-101");
        task.setProjectId(6L);
        task.setAssigneeId(7L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-101", 7L)).thenReturn(task);
        when(sessionMapper.selectOne(any())).thenReturn(null);

        AgentTaskStatusResponse status = service.prepare(7L, "LINEAR-LITE-101");

        assertEquals(false, status.hasSubmittedTurn());
        verify(sessionMapper).insert(any(AgentTaskSession.class));
    }

    @Test
    void submitsAnotherTurnForCompletedTaskWithoutChangingTaskStatus() {
        Task task = new Task();
        task.setId(10L);
        task.setTaskKey("LINEAR-LITE-101");
        task.setProjectId(6L);
        task.setAssigneeId(7L);
        task.setStatus("done");
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-101", 7L)).thenReturn(task);

        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setExecutionId("exec-existing");
        session.setStatus("waiting_input");
        session.setAgentUserId(7L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(jobMapper.selectCount(any())).thenReturn(0L);

        AgentTaskStatusResponse status = service.submitTurn(
                7L, "LINEAR-LITE-101", "exec-existing", "继续修复验收反馈");

        assertEquals("exec-existing", status.executionId());
        assertEquals("queued", status.jobStatus());
        assertEquals("done", task.getStatus());
        verify(jobMapper).insert(any(AgentTaskJob.class));
        verifyNoInteractions(taskMapper);
    }

    @Test
    void cancelsOnlyTaskTheHumanUserCanAccess() {
        Task task = new Task();
        task.setId(10L);
        task.setAssigneeId(7L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-84", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setTaskId(10L);
        session.setStatus("running");
        session.setExecutionId("exec-1");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        service.cancelTask(7L, "LINEAR-LITE-84", "exec-1");

        verify(taskPermissionGuard).requireTaskAccessByKey("LINEAR-LITE-84", 7L);
        verify(sessionMapper).updateById(session);
        verify(jobMapper).update(isNull(), any());
    }

    @Test
    void refusesCancellationBeforeLookingUpSessionWhenTaskIsNotAccessible() {
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-84", 8L))
                .thenThrow(new ConflictOperationException("无权访问任务"));

        assertThrows(ConflictOperationException.class,
                () -> service.cancelTask(8L, "LINEAR-LITE-84", "exec-1"));

        verifyNoInteractions(sessionMapper, jobMapper);
    }

    @Test
    void refusesOldExecutionIdWithoutChangingTheCurrentSession() {
        Task task = new Task();
        task.setId(10L);
        task.setAssigneeId(7L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-84", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setTaskId(10L);
        session.setStatus("running");
        session.setExecutionId("exec-new");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        assertThrows(ConflictOperationException.class,
                () -> service.cancelTask(7L, "LINEAR-LITE-84", "exec-old"));

        verifyNoInteractions(jobMapper);
    }

    @Test
    void claimsTaskWithProjectIdentityAndOpaqueSessionId() {
        AgentTaskJob job = new AgentTaskJob();
        job.setId(9L);
        job.setSessionId(20L);
        job.setExecutionId("exec-1");
        job.setTaskId(10L);
        job.setSourceType("turn");
        job.setPrompt("查询广州明天的天气");
        job.setStatus("queued");
        job.setAttemptCount(0);
        job.setNextRunAt(LocalDateTime.now().minusMinutes(1));

        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setExecutionId("exec-1");
        session.setAgentUserId(7L);
        session.setStatus("running");
        session.setSessionId("pi-session-opaque");

        Task task = new Task();
        task.setId(10L);
        task.setTaskKey("LINEAR-LITE-84");
        task.setProjectId(30L);
        task.setTitle("按项目身份领取任务");
        task.setDescription("查询广州明天的天气");
        task.setAssigneeId(7L);

        Project project = new Project();
        project.setId(30L);
        project.setName("Linear Lite");

        when(jobMapper.selectClaimableForExecution(eq(7L), eq("exec-1"), any(LocalDateTime.class))).thenReturn(job);
        when(sessionMapper.selectById(20L)).thenReturn(session);
        when(taskMapper.selectById(10L)).thenReturn(task);
        when(projectMapper.selectById(30L)).thenReturn(project);

        AgentJobClaimResponse response = service.claim(7L, "exec-1");

        assertEquals(20L, response.sessionId());
        assertEquals("pi-session-opaque", response.piSessionId());
        assertEquals(30L, response.projectId());
        assertEquals("Linear Lite", response.projectName());
        assertEquals("查询广州明天的天气", response.prompt());
        verify(jobMapper).updateById(job);
    }

    @Test
    void taskStatusIgnoresLegacyNonTurnJobsWhenTheSessionIsIdle() {
        Task task = new Task();
        task.setId(10L);
        task.setAssigneeId(7L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-96", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setTaskId(10L);
        session.setExecutionId("exec-1");
        session.setStatus("running");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(jobMapper.selectOne(any())).thenReturn(null);

        AgentTaskStatusResponse status = service.getTaskStatus("LINEAR-LITE-96", 7L);

        assertEquals("exec-1", status.executionId());
        assertEquals(null, status.jobId());
        assertEquals(null, status.sourceType());
        assertEquals(null, status.jobStatus());
    }

    @Test
    void updatesOpaqueSessionIdWithoutAcceptingALocalPath() {
        AgentTaskSession session = new AgentTaskSession();
        session.setExecutionId("exec-1");
        session.setAgentUserId(7L);
        when(sessionMapper.selectOne(any())).thenReturn(session);

        AgentSessionStateRequest request = new AgentSessionStateRequest();
        request.setSessionId("pi-session-1");

        service.updateSessionState(7L, "exec-1", request);

        assertEquals("pi-session-1", session.getSessionId());
        verify(sessionMapper).updateById(session);
    }
}
