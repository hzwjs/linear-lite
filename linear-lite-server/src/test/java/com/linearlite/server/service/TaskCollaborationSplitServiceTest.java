package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.TaskMutationResponse;
import com.linearlite.server.dto.TaskSplitRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskDependency;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.TaskDependencyMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCollaborationSplitServiceTest {
    private final TaskExecutionAccessService access = mock(TaskExecutionAccessService.class);
    private final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskDependencyMapper dependencies = mock(TaskDependencyMapper.class);
    private final CollaborationBatchMapper batches = mock(CollaborationBatchMapper.class);
    private final TaskCommandService taskCommands = mock(TaskCommandService.class);
    private final MarkdownToBlockNoteConverter descriptions = mock(MarkdownToBlockNoteConverter.class);
    private final ObjectMapper json = new ObjectMapper();
    private TaskCollaborationSplitService service;
    private CollaborationBatchRequest batch;
    private CollaborationAttemptCommand command;
    private TaskExecutionAccessService.OwnedAttempt owned;
    private TaskExecutionAttempt attempt;
    private Task parent;

    @BeforeEach void setup() {
        service = new TaskCollaborationSplitService(access, operations, attempts, tasks, dependencies, batches,
                taskCommands, descriptions, json);
        batch = new CollaborationBatchRequest("batch-secret", 30L, "device", "/work");
        command = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "op");
        parent = task(10L, null);
        attempt = new TaskExecutionAttempt();
        attempt.setId(20L);
        attempt.setCheckpoints(json.createArrayNode());
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L, "device",
                List.of(10L), Instant.now().plusSeconds(300), 9L, "host", 1L, Instant.now().plusSeconds(30),
                Instant.now().plusSeconds(300), 5, 1);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, parent, attempt);
        doReturn(owned).when(access).requireOwnedAttempt(1L, batch, 10L, command);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
        when(tasks.selectCount(any())).thenReturn(0L);
        when(dependencies.selectByPrerequisiteTaskId(10L)).thenReturn(List.of());
        when(descriptions.convertTaskDescription(anyString())).thenReturn("[]");
        when(batches.countTasks("batch")).thenReturn(1L);
        when(batches.insertTask(eq("batch"), anyLong())).thenReturn(1);
        when(batches.addAttemptBudget(eq("batch"), anyInt())).thenReturn(1);
        when(tasks.update(any(), any())).thenReturn(1);
        when(attempts.appendCheckpoint(eq(20L), eq(0), anyString())).thenReturn(1);
        long[] next = {100L};
        when(taskCommands.create(eq(6L), eq(1L), isNull(), anyString(), anyString(), eq("todo"), any(), any(), any(), any(), eq(0), any()))
                .thenAnswer(call -> {
                    Task child = task(next[0]++, null);
                    child.setTitle(call.getArgument(3));
                    return new TaskMutationResponse(child, List.of());
                });
    }

    @Test void splitCreatesSubtasksInBatchStopsAttemptAndAddsBudget() {
        var result = service.split(1L, batch, 10L, command, split("接口", "页面"));
        assertTrue(result.path("accepted").asBoolean());
        assertEquals("SPLIT", result.path("outcome").asText());
        assertTrue(result.path("stopRequested").asBoolean());
        assertEquals(2, result.path("addedAttempts").asInt());
        assertEquals(2, result.path("subtasks").size());
        verify(batches).insertTask("batch", 100L);
        verify(batches).insertTask("batch", 101L);
        verify(batches).addAttemptBudget("batch", 2);
        verify(access).requestStop(attempt, "任务已拆分");
        verify(attempts).appendCheckpoint(eq(20L), eq(0), argThat(v -> v.contains("task_split") && !v.contains("claim-secret")));
        verify(operations).save(eq(6L), eq(1L), eq("op"), eq("task_split"), eq(20L), any(), eq(result));
        // 子任务不声明依赖。
        verify(dependencies, never()).insert(any(TaskDependency.class));
    }

    @Test void replayDoesNotCreateAgain() {
        var replay = json.createObjectNode().put("accepted", true);
        when(operations.findReplay(eq(6L), eq(1L), eq("op"), eq("task_split"), any())).thenReturn(replay);
        assertSame(replay, service.split(1L, batch, 10L, command, split("接口")));
        verify(taskCommands, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(access, never()).checkWritable(any(), any());
    }

    @Test void refusesWhenAttemptCannotWriteOrTaskAlreadyHasChildrenOrDownstream() {
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(false, "执行租约过期", attempt));
        assertEquals("执行租约过期", service.split(1L, batch, 10L, command, split("接口")).path("reason").asText());
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
        when(tasks.selectCount(any())).thenReturn(1L);
        assertEquals("任务已有子任务，不能重复拆分", service.split(1L, batch, 10L, command, split("接口")).path("reason").asText());
        when(tasks.selectCount(any())).thenReturn(0L);
        when(dependencies.selectByPrerequisiteTaskId(10L)).thenReturn(List.of(new TaskDependency()));
        assertEquals("任务已被其他任务依赖，不能拆分", service.split(1L, batch, 10L, command, split("接口")).path("reason").asText());
        verify(taskCommands, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void refusesDeepNestingAndOversizedBatch() {
        Task mid = task(5L, 4L), top = task(4L, null);
        parent.setParentId(5L);
        when(tasks.selectById(5L)).thenReturn(mid);
        when(tasks.selectById(4L)).thenReturn(top);
        assertEquals("拆分层级已达上限", service.split(1L, batch, 10L, command, split("接口")).path("reason").asText());
        parent.setParentId(null);
        when(batches.countTasks("batch")).thenReturn(200L);
        assertEquals("批次任务数量已达上限", service.split(1L, batch, 10L, command, split("接口")).path("reason").asText());
    }

    @Test void rejectsInvalidRequests() {
        assertThrows(IllegalArgumentException.class, () -> service.split(1L, batch, 10L, command, new TaskSplitRequest("r", List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.split(1L, batch, 10L, command, split("同名", "同名")));
        assertThrows(IllegalArgumentException.class, () -> service.split(1L, batch, 10L, command, new TaskSplitRequest(" ", List.of(new TaskSplitRequest.Subtask("a", null)))));
        verify(access, never()).requireOwnedAttempt(any(), any(), any(), any());
    }

    private static TaskSplitRequest split(String... titles) {
        return new TaskSplitRequest("包含多个可独立验证的成果",
                java.util.Arrays.stream(titles).map(t -> new TaskSplitRequest.Subtask(t, "需求：" + t)).toList());
    }

    private static Task task(Long id, Long parentId) {
        Task t = new Task();
        t.setId(id); t.setProjectId(6L); t.setTaskKey("T-" + id); t.setTitle("任务" + id); t.setStatus("in_progress");
        t.setPriority("medium"); t.setParentId(parentId); t.setInputRevision(2L);
        return t;
    }
}
