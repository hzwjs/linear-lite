package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.TaskCollaborationState.Request;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCollaborationStateServiceTest {
    private final TaskPermissionGuard permissions = mock(TaskPermissionGuard.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final TaskCollaborationInputService inputs = mock(TaskCollaborationInputService.class);
    private final CollaborationContextService contexts = mock(CollaborationContextService.class);
    private final TaskCollaborationDependencyService dependencies = mock(TaskCollaborationDependencyService.class);
    private final CollaborationGoalRequests goals = mock(CollaborationGoalRequests.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);
    private final TaskCollaborationStateService service = new TaskCollaborationStateService(
            permissions, projects, tasks, bindings, attempts, inputs, contexts, mock(TaskPrerequisiteReviewService.class), dependencies, goals, clock);
    private Task task;

    @BeforeEach void prepare() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "state-test"),
                ProjectDeviceBinding.class);
        task = new Task(); task.setId(1L); task.setProjectId(6L); task.setInputRevision(4L);
        task.setStatus("todo");
        task.setProgressPercent(0);
        when(permissions.requireTaskAccessByKey("TEST-1", 7L)).thenReturn(task);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(task);
    }

    @Test void returnsCurrentInputVersionWithExecutionFacts() {
        task.setInputRevision(8L);
        var state = service.get(7L, new Request("TEST-1"));
        assertEquals(8L, state.inputRevision());
        verify(permissions).requireProjectMember(6L, 7L);
    }

    @Test void accessAndProjectMembershipAreRequiredBeforeReadingHistory() {
        doThrow(new ForbiddenOperationException("非项目成员")).when(permissions).requireProjectMember(6L, 7L);
        assertThrows(ForbiddenOperationException.class, () -> service.get(7L, new Request("TEST-1")));
        verifyNoInteractions(inputs, bindings, attempts);
    }

    @Test void deletedOrMovedTaskCannotReuseAnEarlierAccessDecision() {
        Task moved = new Task(); moved.setProjectId(9L);
        when(tasks.selectByIdForUpdate(1L)).thenReturn(moved);
        assertThrows(ResourceNotFoundException.class, () -> service.get(7L, new Request("TEST-1")));
        verifyNoInteractions(inputs, bindings, attempts);
    }

    @Test void bindingReadinessDoesNotExposePathOwners() {
        ProjectDeviceBinding binding = binding(10L, 7L, 6L, "device-a");
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        var prerequisite = new TaskCollaborationInputService.PrerequisiteResult(2L, 20L, 3L,
                json.createObjectNode().put("artifact", "docs/验收/生产者.md"), List.of());
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of(prerequisite)));
        TaskExecutionAttempt held = new TaskExecutionAttempt(); held.setTaskId(3L); held.setBindingId(10L);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(held));
        var state = service.get(7L, new Request("TEST-1"));
        assertEquals(4L, state.inputRevision()); assertFalse(state.activeOrFrozen());
        var evaluation = state.bindings().get(0);
        assertTrue(evaluation.inputsReady());
        assertTrue(evaluation.prerequisiteReviewStatuses().get(0).adoptionRequired());
        assertEquals(List.of(prerequisite), evaluation.prerequisiteResults());
        @SuppressWarnings("rawtypes") var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(bindings).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id")); assertTrue(sql.contains("project_id"));
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(6L));
        verify(attempts, never()).requestStopForTask(anyLong(), any(), anyString());
    }

    @Test void expiredRunningHistoryStaysFrozenAndDoesNotLeakCredentialsOrOtherUserDirectories() throws Exception {
        TaskExecutionAttempt running = new TaskExecutionAttempt(); running.setId(30L); running.setTaskId(1L);
        running.setBindingId(11L); running.setInputRevision(3L); running.setStatus("running");
        running.setLeaseUntil(LocalDateTime.of(2026, 11, 10, 15, 59));
        running.setClaimTokenHash("fixture-private-claim-hash");
        when(attempts.selectByTaskId(1L)).thenReturn(List.of(running));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(running));
        ProjectDeviceBinding other = binding(11L, 8L, 6L, "other-device");
        other.setProjectDirectory("/private/other-user/project");
        other.setDeletedAt(LocalDateTime.of(2026, 11, 9, 16, 0));
        when(bindings.selectById(11L)).thenReturn(other);
        var state = service.get(7L, new Request("TEST-1"));
        assertTrue(state.activeOrFrozen()); assertEquals(clock.instant(), state.serverTime());
        assertEquals("running", state.attempts().get(0).status());
        assertTrue(state.attempts().get(0).leaseUntil().isBefore(state.serverTime()));
        assertNull(state.attempts().get(0).stopConfirmedAt());
        assertEquals("other-device", state.attemptBindings().get(0).deviceId());
        String encoded = json.writeValueAsString(state);
        assertFalse(encoded.contains("claimTokenHash")); assertFalse(encoded.contains("fixture-private-claim-hash"));
        assertFalse(encoded.contains("/private/other-user")); assertFalse(encoded.contains("agentExecutionEnabled"));
        assertEquals("running", running.getStatus());
        verify(attempts, never()).updateById(any(TaskExecutionAttempt.class));
        verify(attempts, never()).requestStopForTask(anyLong(), any(), anyString());
    }

    @Test void invalidRequestDoesNotReadAnyTask() {
        assertThrows(IllegalArgumentException.class, () -> service.get(7L, new Request("")));
        verifyNoInteractions(permissions, projects, tasks, bindings, attempts);
    }

    @Test void invalidatedBusinessStateComesFromTheSameInputRevision() {
        task.setUpdatedAt(LocalDateTime.of(2026, 11, 10, 16, 0));
        task.setCompletedAt(null);
        var state = service.get(7L, new Request("TEST-1"));
        assertEquals("todo", state.status());
        assertEquals(0, state.progressPercent());
        assertNull(state.completedAt());
        assertEquals(task.getUpdatedAt(), state.updatedAt());
        assertEquals(4L, state.inputRevision());
    }

    private ProjectDeviceBinding binding(Long id, Long userId, Long projectId, String deviceId) {
        ProjectDeviceBinding binding = new ProjectDeviceBinding(); binding.setId(id); binding.setUserId(userId);
        binding.setProjectId(projectId); binding.setDeviceId(deviceId); return binding;
    }
}
