package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.DependencyChange;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.entity.TaskExecutionOperation;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import com.linearlite.server.mapper.TaskMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCollaborationDependencyServiceTest {
    private final TaskExecutionAccessService access = mock(TaskExecutionAccessService.class);
    private final TaskCollaborationInputService inputs = mock(TaskCollaborationInputService.class);
    private final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    private final TaskExecutionOperationMapper operationMapper = mock(TaskExecutionOperationMapper.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private TaskCollaborationDependencyService service;
    private CollaborationBatchRequest batch;
    private CollaborationAttemptCommand command;
    private TaskExecutionAccessService.OwnedAttempt owned;
    private Task task;

    @BeforeEach void setup() {
        service = new TaskCollaborationDependencyService(access, inputs, operations, operationMapper, attempts, tasks, json);
        batch = new CollaborationBatchRequest("batch-secret", 30L, "device", "/work");
        command = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "op");
        task = task(10L, 6L, "in_progress", 2L, "当前目标");
        var attempt = new TaskExecutionAttempt();
        attempt.setId(20L);
        attempt.setCheckpoints(json.createArrayNode());
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L, 11L), Instant.now().plusSeconds(300), 9L, "host", 1L,
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(300), 5, 2);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, task, attempt);
        doReturn(owned).when(access).requireOwnedAttempt(1L, batch, 10L, command);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
        when(attempts.appendCheckpoint(eq(20L), eq(0), anyString())).thenReturn(1);
        when(tasks.selectById(11L)).thenReturn(task(11L, 6L, "todo", 1L, "接口成果"));
    }

    @Test void changeUsesCurrentAttemptAndPersistsFactAndReplay() {
        var change = add(11L);
        when(inputs.changeDependency(task, 11L, true, 1L)).thenReturn(true);
        var result = service.change(1L, batch, 10L, command, change);
        assertTrue(result.path("accepted").asBoolean());
        assertTrue(result.path("changed").asBoolean());
        assertEquals(3, result.path("inputRevision").asInt());
        assertTrue(result.path("stopRequested").asBoolean());
        verify(attempts).appendCheckpoint(eq(20L), eq(0), argThat(value -> value.contains("接口尚未完成") && !value.contains("claim-secret")));
        verify(operations).save(eq(6L), eq(1L), eq("op"), eq("dependency_change"), eq(20L), any(), eq(result));
    }

    @Test void replayPrecedesWritableCheckAndDoesNotMutateAgain() {
        var replay = json.createObjectNode().put("accepted", true).put("changed", true);
        when(operations.findReplay(eq(6L), eq(1L), eq("op"), eq("dependency_change"), any())).thenReturn(replay);
        assertEquals(replay, service.change(1L, batch, 10L, command, add(11L)));
        verify(access, never()).checkWritable(any(), any());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
    }

    @Test void invalidClaimAndStaleAttemptCannotChangeRelations() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenThrow(new ForbiddenOperationException("归属不匹配"));
        assertThrows(ForbiddenOperationException.class, () -> service.change(1L, batch, 10L, command, add(11L)));
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
        doReturn(owned).when(access).requireOwnedAttempt(1L, batch, 10L, command);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "输入失效", owned.attempt()));
        assertFalse(service.change(1L, batch, 10L, command, add(11L)).path("accepted").asBoolean());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
    }

    @Test void missingOutsideBatchDoesNotCreateDependencyOrExpandAuthorization() {
        when(tasks.selectById(12L)).thenReturn(task(12L, 6L, "todo", 1L, "物流接口"));
        var result = service.change(1L, batch, 10L, command, add(12L));
        assertEquals("MISSING_OUTSIDE_BATCH", result.path("outcome").asText());
        assertEquals("物流接口", result.path("goal").asText());
        assertTrue(result.path("guidance").asText().contains("新批次"));
        assertFalse(result.path("stopRequested").asBoolean());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
    }

    @Test void outsideDeliveredResultCanBeReferencedWithoutBatchExpansion() {
        Task prerequisite = task(12L, 6L, "done", 3L, "物流接口");
        when(tasks.selectById(12L)).thenReturn(prerequisite);
        var delivered = new TaskExecutionAttempt();
        delivered.setBindingId(30L); delivered.setInputRevision(3L); delivered.setStatus("succeeded");
        delivered.setStopConfirmedAt(LocalDateTime.now()); delivered.setResultEvidence(json.createObjectNode());
        when(inputs.hasExplanatoryDelivery(prerequisite, 30L)).thenReturn(true);
        when(inputs.changeDependency(task, 12L, true, 1L)).thenReturn(true);
        assertTrue(service.change(1L, batch, 10L, command, add(12L)).path("changed").asBoolean());
        verify(inputs).changeDependency(task, 12L, true, 1L);
    }

    @Test void outsideBatchRecordedTraceCanFormDependency() {
        Task prerequisite = task(12L, 6L, "in_progress", 3L, "物流接口");
        when(tasks.selectById(12L)).thenReturn(prerequisite);
        when(inputs.currentTrace(prerequisite, 30L)).thenReturn(
                new TaskCollaborationInputService.PrerequisiteResult(12L, 21L, 3L, json.createObjectNode(), List.of()));
        when(inputs.changeDependency(task, 12L, true, 1L)).thenReturn(true);
        assertTrue(service.change(1L, batch, 10L, command, add(12L)).path("changed").asBoolean());
    }

    @Test void explicitNewGoalStopsAttemptWithoutInventingTaskOrRelation() {
        var request = new DependencyChange("REQUEST_GOAL", null, "缺少物流接口", "目标扩大", "需求文档", "物流能力", "需要新增服务");
        var result = service.change(1L, batch, 10L, command, request);
        assertEquals("GOAL_AUTHORIZATION_REQUIRED", result.path("outcome").asText());
        assertTrue(result.path("stopRequested").asBoolean());
        verify(access).requestStop(owned.attempt(), "等待业务目标授权");
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
        verifyNoInteractions(tasks);
    }

    @Test void factProjectionExposesOnlyBusinessFields() {
        var row = new TaskExecutionOperation();
        row.setOperationId("op"); row.setAttemptId(20L); row.setUserId(1L);
        row.setRequestJson("{\"taskId\":10,\"batchId\":\"internal\",\"inputRevision\":2,\"change\":{\"action\":\"ADD\",\"prerequisiteTaskId\":11,\"missing\":\"接口尚未完成\",\"reason\":\"调用失败\",\"evidence\":\"接口规范\",\"goal\":null,\"impact\":null}}");
        row.setResponseJson("{\"accepted\":true,\"changed\":true,\"outcome\":\"RECORDED\"}");
        when(operationMapper.selectDependencyFacts(6L, 10L)).thenReturn(List.of(row));
        var fact = service.listFacts(6L, 10L).get(0);
        assertEquals("接口尚未完成", fact.change().missing());
        assertEquals("internal", fact.batchId());
        assertFalse(json.valueToTree(fact).toString().contains("claim-secret"));
    }

    @Test void goalAuthorizationBlocksOnlyOriginatingBatch() {
        var row = new TaskExecutionOperation();
        row.setOperationId("op"); row.setAttemptId(20L); row.setUserId(1L);
        row.setRequestJson("{\"taskId\":10,\"batchId\":\"batch-a\",\"inputRevision\":2,\"change\":{\"action\":\"REQUEST_GOAL\",\"prerequisiteTaskId\":null,\"missing\":\"接口\",\"reason\":\"目标变化\",\"evidence\":\"需求\",\"goal\":\"物流能力\",\"impact\":\"新增服务\"}}");
        row.setResponseJson("{\"accepted\":true,\"changed\":false,\"outcome\":\"GOAL_AUTHORIZATION_REQUIRED\"}");
        when(operationMapper.selectDependencyFacts(6L, 10L)).thenReturn(List.of(row));
        assertEquals("新增业务目标等待授权：物流能力", service.goalAuthorizationBlock(6L, 10L, "batch-a", 2L));
        assertNull(service.goalAuthorizationBlock(6L, 10L, "batch-b", 2L));
        assertNull(service.goalAuthorizationBlock(6L, 10L, "batch-a", 3L));
        row.setResponseJson("{\"accepted\":false,\"changed\":false,\"outcome\":\"GOAL_AUTHORIZATION_REQUIRED\"}");
        assertNull(service.goalAuthorizationBlock(6L, 10L, "batch-a", 2L));
    }

    @Test void credentialInFactIsRejected() {
        var change = new DependencyChange("ADD", 11L, "claim-secret", "原因", "证据", null, null);
        assertThrows(IllegalArgumentException.class, () -> service.change(1L, batch, 10L, command, change));
        verifyNoInteractions(operations);
    }

    @Test void confirmedLatestAttemptCanRetractItsOwnStillCurrentEdge() {
        var stopped = prepareRetraction();
        when(inputs.changeDependency(task, 11L, false, 1L)).thenReturn(true);
        var result = service.change(1L, batch, 10L, stopped, remove(11L));
        assertTrue(result.path("accepted").asBoolean());
        assertTrue(result.path("changed").asBoolean());
        assertEquals(4, result.path("inputRevision").asInt());
        assertTrue(result.path("retracted").asBoolean());
        assertFalse(result.path("stopRequested").asBoolean());
        verify(inputs).changeDependency(task, 11L, false, 1L);
        verify(attempts).appendCheckpoint(eq(20L), eq(0), contains("REMOVE"));
    }

    @Test void retractionRejectsOtherOwnerReplacementOrNoCurrentEdge() {
        var stopped = prepareRetraction();
        var add = dependencyFact("ADD", 21L);
        when(operationMapper.selectDependencyFacts(6L, 10L)).thenReturn(List.of(add));
        assertFalse(service.change(1L, batch, 10L, stopped, remove(11L)).path("accepted").asBoolean());
        when(operationMapper.selectDependencyFacts(6L, 10L)).thenReturn(List.of(dependencyFact("ADD", 20L),
                dependencyFact("REMOVE", 21L), dependencyFact("ADD", 21L)));
        assertFalse(service.change(1L, batch, 10L, stopped, remove(11L)).path("accepted").asBoolean());
        when(operationMapper.selectDependencyFacts(6L, 10L)).thenReturn(List.of(dependencyFact("ADD", 20L)));
        when(inputs.hasDependency(10L, 11L)).thenReturn(false);
        assertFalse(service.change(1L, batch, 10L, stopped, remove(11L)).path("accepted").asBoolean());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
    }

    @Test void retractionRejectsNewerAttemptActiveAttemptOrStaleRevision() {
        var stopped = prepareRetraction();
        var newer = new TaskExecutionAttempt(); newer.setId(21L); newer.setStopConfirmedAt(LocalDateTime.now());
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(owned.attempt(), newer));
        assertFalse(service.change(1L, batch, 10L, stopped, remove(11L)).path("accepted").asBoolean());
        newer.setStopConfirmedAt(null);
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(newer, owned.attempt()));
        assertFalse(service.change(1L, batch, 10L, stopped, remove(11L)).path("accepted").asBoolean());
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(owned.attempt()));
        task.setInputRevision(4L);
        assertFalse(service.change(1L, batch, 10L, stopped, remove(11L)).path("accepted").asBoolean());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
    }

    @Test void stoppedAttemptCannotCreateEdgeOrRequestNewBusinessGoal() {
        var stopped = prepareRetraction();
        assertFalse(service.change(1L, batch, 10L, stopped, add(11L)).path("accepted").asBoolean());
        var goal = new DependencyChange("REQUEST_GOAL", null, "接口", "目标变化", "需求", "物流", "新增服务");
        assertFalse(service.change(1L, batch, 10L, stopped, goal).path("accepted").asBoolean());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
        verify(access, never()).requestStop(any(), any());
    }

    @Test void businessInputChangeAfterOriginalAddRejectsRetractionEvenAtCurrentRevision() {
        prepareRetraction();
        task.setInputRevision(4L);
        var current = new CollaborationAttemptCommand(20L, "claim-secret", 4L, "host", "agent", "new-retract");
        doReturn(owned).when(access).requireOwnedAttempt(1L, batch, 10L, current);
        when(access.checkWritable(owned, current)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "尝试停止", owned.attempt()));
        assertFalse(service.change(1L, batch, 10L, current, remove(11L)).path("accepted").asBoolean());
        verify(inputs, never()).changeDependency(any(), any(), anyBoolean(), any());
    }

    private CollaborationAttemptCommand prepareRetraction() {
        task.setInputRevision(3L);
        owned.attempt().setStopRequestedAt(LocalDateTime.now().minusMinutes(1));
        owned.attempt().setStopConfirmedAt(LocalDateTime.now());
        var stopped = new CollaborationAttemptCommand(20L, "claim-secret", 3L, "host", "agent", "retract");
        doReturn(owned).when(access).requireOwnedAttempt(1L, batch, 10L, stopped);
        when(access.checkWritable(owned, stopped)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "尝试停止", owned.attempt()));
        when(attempts.selectByTaskId(10L)).thenReturn(List.of(owned.attempt()));
        when(operationMapper.selectDependencyFacts(6L, 10L)).thenReturn(List.of(dependencyFact("ADD", 20L)));
        when(inputs.hasDependency(10L, 11L)).thenReturn(true);
        return stopped;
    }

    private TaskExecutionOperation dependencyFact(String action, Long attemptId) {
        var row = new TaskExecutionOperation();
        row.setOperationId("old"); row.setAttemptId(attemptId); row.setUserId(1L);
        row.setRequestJson("{\"taskId\":10,\"batchId\":\"batch\",\"inputRevision\":2,\"change\":{\"action\":\"" + action
                + "\",\"prerequisiteTaskId\":11,\"missing\":\"接口\",\"reason\":\"调用失败\",\"evidence\":\"规范\",\"goal\":null,\"impact\":null}}");
        row.setResponseJson("{\"accepted\":true,\"changed\":true,\"outcome\":\"RECORDED\",\"inputRevision\":3}");
        return row;
    }

    private DependencyChange remove(Long id) {
        return new DependencyChange("REMOVE", id, "接口已实现", "关系判断有误", "当前代码", null, null);
    }

    private DependencyChange add(Long id) {
        return new DependencyChange("ADD", id, "接口尚未完成", "调用失败", "接口规范", null, null);
    }
    private Task task(Long id, Long project, String status, Long revision, String title) {
        Task value = new Task(); value.setId(id); value.setProjectId(project); value.setStatus(status);
        value.setInputRevision(revision); value.setTitle(title); return value;
    }
}
