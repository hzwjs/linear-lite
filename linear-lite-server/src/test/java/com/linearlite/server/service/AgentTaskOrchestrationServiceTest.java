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
import com.linearlite.server.mapper.ProjectAgentBindingMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskOrchestrationServiceTest {
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectAgentBindingMapper bindingMapper = mock(ProjectAgentBindingMapper.class);
    private final AgentTaskSessionMapper sessionMapper = mock(AgentTaskSessionMapper.class);
    private final AgentTaskJobMapper jobMapper = mock(AgentTaskJobMapper.class);
    private final TaskPermissionGuard taskPermissionGuard = mock(TaskPermissionGuard.class);
    private final AgentTaskOrchestrationService service = new AgentTaskOrchestrationService(
            userMapper, taskMapper, projectMapper, bindingMapper, sessionMapper, jobMapper, taskPermissionGuard);

    @Test
    void cancelsOnlyTaskTheHumanUserCanAccess() {
        Task task = new Task();
        task.setId(10L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-84", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setTaskId(10L);
        session.setStatus("active");
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
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-84", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setTaskId(10L);
        session.setStatus("active");
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
        job.setSourceType("assignment");
        job.setStatus("queued");
        job.setAttemptCount(0);
        job.setNextRunAt(LocalDateTime.now().minusMinutes(1));

        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setExecutionId("exec-1");
        session.setAgentUserId(7L);
        session.setStatus("active");
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

        when(jobMapper.selectOne(any())).thenReturn(job);
        when(sessionMapper.selectById(20L)).thenReturn(session);
        when(taskMapper.selectById(10L)).thenReturn(task);
        when(projectMapper.selectById(30L)).thenReturn(project);

        AgentJobClaimResponse response = service.claim(7L);

        assertEquals(20L, response.sessionId());
        assertEquals(30L, response.projectId());
        assertEquals("Linear Lite", response.projectName());
        assertEquals("查询广州明天的天气", response.prompt());
        verify(jobMapper).updateById(job);
    }

    @Test
    void taskStatusIdentifiesTheLatestCommentJobWithinTheSameExecution() {
        Task task = new Task();
        task.setId(10L);
        when(taskPermissionGuard.requireTaskAccessByKey("LINEAR-LITE-96", 7L)).thenReturn(task);
        AgentTaskSession session = new AgentTaskSession();
        session.setId(20L);
        session.setTaskId(10L);
        session.setExecutionId("exec-1");
        session.setStatus("active");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        AgentTaskJob commentJob = new AgentTaskJob();
        commentJob.setId(22L);
        commentJob.setSessionId(20L);
        commentJob.setStatus("queued");
        commentJob.setSourceType("comment");
        when(jobMapper.selectOne(any())).thenReturn(commentJob);

        AgentTaskStatusResponse status = service.getTaskStatus("LINEAR-LITE-96", 7L);

        assertEquals("exec-1", status.executionId());
        assertEquals(22L, status.jobId());
        assertEquals("comment", status.sourceType());
        assertEquals("queued", status.jobStatus());
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
