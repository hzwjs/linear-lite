package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationBatchDtos.*;
import com.linearlite.server.dto.GoalAuthorizationRequest;
import com.linearlite.server.dto.TaskMutationResponse;
import com.linearlite.server.dto.CollaborationContextRef;
import com.linearlite.server.dto.DependencyChange;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollaborationBatchControlServiceTest {
    private final CollaborationBatchMapper batches = mock(CollaborationBatchMapper.class);
    private final ProjectDeviceBindingMapper bindings = mock(ProjectDeviceBindingMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskCollaborationDependencyService dependencies = mock(TaskCollaborationDependencyService.class);
    private final CollaborationGoalRequests goals = mock(CollaborationGoalRequests.class);
    private final CollaborationContextService contexts = mock(CollaborationContextService.class);
    private final TaskCommandService taskCommands = mock(TaskCommandService.class);
    private final MarkdownToBlockNoteConverter descriptions = mock(MarkdownToBlockNoteConverter.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final HostDeviceAuthorizationService hosts = mock(HostDeviceAuthorizationService.class);
    private final TaskCollaborationInputService inputs = mock(TaskCollaborationInputService.class);
    private final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);
    private final TaskPrerequisiteReviewService reviews = mock(TaskPrerequisiteReviewService.class);
    private CollaborationBatchControlService service;

    @BeforeEach void setUp() {
        service = new CollaborationBatchControlService(batches, bindings, projects, tasks, attempts,
                access, hosts, inputs, operations, json, reviews, contexts, dependencies, goals, taskCommands, descriptions, clock);
    }

    @Test void startFixesSelectedTasksGrantBindingAndBudgetWithoutIssuingToken() {
        prepareStart();
        var result = service.start(7L, startRequest());
        var captured = ArgumentCaptor.forClass(CollaborationBatch.class);
        verify(batches).insert(captured.capture());
        CollaborationBatch row = captured.getValue();
        assertEquals(result.path("batchId").textValue(), row.getBatchId());
        assertEquals(30L, row.getHostAuthorizationId());
        assertEquals(2L, row.getBindingRevision());
        assertEquals(1, row.getMaxSlots());
        assertEquals(60000L, row.getMaxDurationMs());
        assertEquals(5, row.getMaxAttempts());
        assertEquals("queued", row.getLifecycle());
        assertEquals("RUN", row.getDesiredIntent());
        assertNull(row.getStartedAt());
        assertNull(row.getDeadlineAt());
        verify(batches).insertTask(row.getBatchId(), 101L);
        verify(batches).insertTask(row.getBatchId(), 102L);
        verify(operations).save(eq(6L), eq(7L), eq("start-1"), eq("batch_start"), isNull(), any(), eq(result));
        assertFalse(result.has("batchToken"));
        InOrder locks = inOrder(hosts, projects, bindings);
        locks.verify(hosts).requireOnlineGrant(7L, "device-A");
        locks.verify(projects).lockProject(6L);
        locks.verify(bindings).selectForUpdate(7L, 6L, "device-A");
    }

    @Test void startSingleUsesTheOnlyOnlineBindingAndDefaultDuration() {
        prepareStart();
        when(bindings.selectList(any())).thenReturn(List.of(binding(7L, 2L)));
        when(hosts.isOnlineDevice(7L, "device-A")).thenReturn(true);
        service.startSingle(7L, new StartSingleRequest(6L, "single-1", 101L, null));
        var batch = ArgumentCaptor.forClass(CollaborationBatch.class);
        verify(batches).insert(batch.capture());
        assertEquals(10L, batch.getValue().getBindingId());
        assertEquals(2L * 3600 * 1000, batch.getValue().getMaxDurationMs());
        verify(batches).insertTask(batch.getValue().getBatchId(), 101L);
        verify(batches, never()).insertTask(anyString(), eq(102L));
    }

    @Test void startSingleRefusesToGuessAmongSeveralOrZeroOnlineDevices() {
        prepareStart();
        ProjectDeviceBinding other = binding(7L, 3L); other.setId(11L); other.setDeviceId("device-B");
        when(bindings.selectList(any())).thenReturn(List.of(binding(7L, 2L), other));
        when(hosts.isOnlineDevice(eq(7L), anyString())).thenReturn(true);
        assertThrows(ConflictOperationException.class, () -> service.startSingle(7L, new StartSingleRequest(6L, "s", 101L, null)));
        when(hosts.isOnlineDevice(eq(7L), anyString())).thenReturn(false);
        assertThrows(ConflictOperationException.class, () -> service.startSingle(7L, new StartSingleRequest(6L, "s", 101L, null)));
        verify(batches, never()).insert(any(CollaborationBatch.class));
    }

    @Test void startSingleHonoursExplicitBindingWithoutListingDevices() {
        prepareStart();
        service.startSingle(7L, new StartSingleRequest(6L, "single-2", 101L, 10L));
        verify(bindings, never()).selectList(any());
        verify(batches).insert(any(CollaborationBatch.class));
    }

    @Test void serverBudgetCoversMoreThanFiveSelectedTasks() {
        prepareStart();
        for (long id = 101; id <= 106; id++) {
            when(tasks.selectByIdForUpdate(id)).thenReturn(task(id, true));
        }
        var request = new StartRequest(6L, "six-tasks", 10L, 2L,
                List.of(101L, 102L, 103L, 104L, 105L, 106L), 60000L);
        service.start(7L, request);
        var batch = ArgumentCaptor.forClass(CollaborationBatch.class);
        verify(batches).insert(batch.capture());
        assertEquals(1, batch.getValue().getMaxSlots());
        assertEquals(6, batch.getValue().getMaxAttempts());
        verify(batches, times(6)).insertTask(anyString(), anyLong());
    }

    @Test void rejectsInvalidDurationAndOversizedTaskSelectionBeforeMutation() {
        for (Long duration : List.of(0L, -1L, Long.MAX_VALUE / 1_000_000L + 1, Long.MAX_VALUE)) {
            var request = new StartRequest(6L, "invalid", 10L, 2L, List.of(101L), duration);
            assertThrows(IllegalArgumentException.class, () -> service.start(7L, request));
        }
        var tooMany = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();
        var request = new StartRequest(6L, "too-many", 10L, 2L, tooMany, 1000L);
        assertThrows(IllegalArgumentException.class, () -> service.start(7L, request));
        verifyNoInteractions(batches, hosts, bindings);
    }

    @Test void committedReplayDoesNotRequireHostOnlineOrResetPersistedBudget() {
        var original = json.createObjectNode().put("batchId", "saved-batch").put("lifecycle", "queued");
        when(operations.findCommittedReplay(eq(6L), eq(7L), eq("start-1"), eq("batch_start"), any()))
                .thenReturn(original);
        assertEquals(original, service.start(7L, startRequest()));
        verify(access).requireMember(6L, 7L);
        verifyNoInteractions(hosts, bindings, batches);
    }

    @Test void replayIsRecheckedAfterProjectLockBeforeAnyInsert() {
        ProjectDeviceBinding binding = binding(7L, 2L);
        when(bindings.selectById(10L)).thenReturn(binding);
        when(hosts.requireOnlineGrant(7L, "device-A")).thenReturn(grant());
        when(projects.lockProject(6L)).thenReturn(6L);
        var original = json.createObjectNode().put("batchId", "other-request-finished");
        when(operations.findReplay(eq(6L), eq(7L), eq("start-1"), eq("batch_start"), any())).thenReturn(original);
        assertEquals(original, service.start(7L, startRequest()));
        verify(batches, never()).insert(any(CollaborationBatch.class));
        verify(bindings, never()).selectForUpdate(anyLong(), anyLong(), anyString());
    }

    @Test void deniesOtherUsersBindingAndStaleRevisionWithoutCreatingBatch() {
        when(bindings.selectById(10L)).thenReturn(binding(8L, 2L));
        assertThrows(ResourceNotFoundException.class, () -> service.start(7L, startRequest()));
        verifyNoInteractions(hosts);
        reset(bindings);
        when(bindings.selectById(10L)).thenReturn(binding(7L, 2L));
        when(bindings.selectForUpdate(7L, 6L, "device-A")).thenReturn(binding(7L, 3L));
        when(hosts.requireOnlineGrant(7L, "device-A")).thenReturn(grant());
        when(projects.lockProject(6L)).thenReturn(6L);
        assertThrows(ConflictOperationException.class, () -> service.start(7L, startRequest()));
        verify(batches, never()).insert(any(CollaborationBatch.class));
    }

    @Test void rejectsUnauthorizedTaskAndInvalidConfigurationButLeavesDependencyWaitingToExecution() {
        prepareStart();
        Task unauthorized = task(101L, false); unauthorized.setStatus("backlog");
        when(tasks.selectByIdForUpdate(101L)).thenReturn(unauthorized);
        assertThrows(ConflictOperationException.class, () -> service.start(7L, startRequest()));
        verify(batches, never()).insert(any(CollaborationBatch.class));
        Task valid = task(101L, true);
        when(tasks.selectByIdForUpdate(101L)).thenReturn(valid);
        when(inputs.evaluateConfiguration(valid, 4L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(false, "公共约定不可用或版本已变化", List.of()));
        assertThrows(ConflictOperationException.class, () -> service.start(7L, startRequest()));
        verify(inputs, never()).evaluateInputs(any(), any(), any());
        verify(batches, never()).insert(any(CollaborationBatch.class));
    }

    @Test void rejectsNonMembersAndInvalidTaskSetBeforeAnyMutation() {
        doThrow(new ForbiddenOperationException("无项目权限")).when(access).requireMember(6L, 8L);
        assertThrows(ForbiddenOperationException.class, () -> service.start(8L, startRequest()));
        assertThrows(IllegalArgumentException.class, () -> service.start(7L,
                new StartRequest(6L, "x", 10L, 2L, List.of(101L, 101L), 1000L)));
        verifyNoInteractions(batches, hosts);
    }

    @Test void stopQueuedEndsButAcceptedRemainsStoppingUntilConfirmation() {
        when(projects.lockProject(6L)).thenReturn(6L);
        CollaborationBatch queued = batch("queued", "RUN");
        CollaborationBatch canceled = batch("ended", "STOP");
        canceled.setIntentRevision(2L);
        when(batches.selectForUpdate("b-1")).thenReturn(queued, canceled);
        when(batches.requestStop(eq("b-1"), any())).thenReturn(1);
        var ended = service.stop(7L, new StopRequest(6L, "stop-1", "b-1"));
        assertEquals("ENDED", ended.path("displayState").asText());
        verify(operations).save(eq(6L), eq(7L), eq("stop-1"), eq("batch_stop"), isNull(), any(), eq(ended));

        reset(batches);
        CollaborationBatch active = batch("accepted", "RUN");
        active.setRunLeaseUntil(LocalDateTime.of(2026, 11, 10, 8, 0, 30));
        CollaborationBatch stopping = batch("accepted", "STOP");
        stopping.setIntentRevision(2L);
        stopping.setRunLeaseUntil(active.getRunLeaseUntil());
        when(batches.selectForUpdate("b-1")).thenReturn(active, stopping);
        when(batches.requestStop(eq("b-1"), any())).thenReturn(1);
        var result = service.stop(7L, new StopRequest(6L, "stop-2", "b-1"));
        assertEquals("STOPPING", result.path("displayState").asText());
        assertEquals("accepted", result.path("lifecycle").asText());
        verifyNoInteractions(hosts);
    }

    @Test void stopOtherUsersBatchIsRejectedAndUnavailableLeaseIsRecovery() {
        when(projects.lockProject(6L)).thenReturn(6L);
        CollaborationBatch another = batch("accepted", "RUN");
        another.setUserId(8L);
        when(batches.selectForUpdate("b-1")).thenReturn(another);
        assertThrows(ResourceNotFoundException.class, () -> service.stop(7L, new StopRequest(6L, "s1", "b-1")));
        verify(batches, never()).requestStop(anyString(), any());

        CollaborationBatch active = batch("accepted", "RUN");
        CollaborationBatch stoppedIntent = batch("accepted", "STOP");
        stoppedIntent.setRunLeaseUntil(LocalDateTime.of(2026, 11, 10, 7, 59, 59));
        when(batches.selectForUpdate("b-1")).thenReturn(active, stoppedIntent);
        when(batches.requestStop(eq("b-1"), any())).thenReturn(1);
        assertEquals("RECOVERY", service.stop(7L, new StopRequest(6L, "s2", "b-1"))
                .path("displayState").asText());
    }

    @Test void acceptChecksOriginalGrantAndRefusesBatchStoppedBeforeLock() {
        CollaborationBatch observed = batch("queued", "RUN");
        CollaborationBatch canceled = batch("ended", "STOP");
        when(batches.selectById("b-1")).thenReturn(observed);
        when(bindings.selectById(10L)).thenReturn(binding(7L, 2L));
        when(hosts.requireOnlineGrant(7L, "device-A")).thenReturn(grant());
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(7L, 6L, "device-A")).thenReturn(binding(7L, 2L));
        when(batches.selectForUpdate("b-1")).thenReturn(canceled);
        assertFalse(service.acceptQueued(7L, 6L, "b-1", 30L, "host-1"));
        verify(batches, never()).acceptQueued(anyString(), anyString(), any(), any(), any());
        assertThrows(ConflictOperationException.class, () -> service.acceptQueued(7L, 6L, "b-1", 31L, "host-1"));
    }

    @Test void acceptSetsDeadlineOnceForSamePersistedBatch() {
        CollaborationBatch queued = batch("queued", "RUN");
        when(batches.selectById("b-1")).thenReturn(queued);
        when(bindings.selectById(10L)).thenReturn(binding(7L, 2L));
        when(hosts.requireOnlineGrant(7L, "device-A")).thenReturn(grant());
        when(projects.lockProject(6L)).thenReturn(6L);
        when(bindings.selectForUpdate(7L, 6L, "device-A")).thenReturn(binding(7L, 2L));
        when(batches.selectForUpdate("b-1")).thenReturn(queued);
        when(batches.acceptQueued(anyString(), anyString(), any(), any(), any())).thenReturn(1);
        assertTrue(service.acceptQueued(7L, 6L, "b-1", 30L, "host-1"));
        verify(batches).acceptQueued("b-1", "host-1", LocalDateTime.of(2026, 11, 10, 8, 0, 30),
                LocalDateTime.of(2026, 11, 10, 8, 0), LocalDateTime.of(2026, 11, 10, 8, 1));
    }

    @Test void readModelUsesExistingInputsDependenciesAndOnlyThisBatchAttempts() {
        CollaborationBatch batch = batch("queued", "RUN");
        Task task = task(101L, true);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(batches.selectById("b-1")).thenReturn(batch);
        when(batches.selectTaskIds("b-1")).thenReturn(List.of(101L));
        when(tasks.selectById(101L)).thenReturn(task);
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(
                false, "前置任务缺少成功成果", List.of()));
        TaskExecutionAttempt own = new TaskExecutionAttempt(); own.setId(1L); own.setBatchId("b-1");
        TaskExecutionAttempt other = new TaskExecutionAttempt(); other.setId(2L); other.setBatchId("b-2");
        when(attempts.selectByTaskId(101L)).thenReturn(List.of(own, other));
        var ref = new CollaborationContextRef("DOCUMENT", 7L, 3L, null, null);
        var context = new CollaborationContextService.ContextStatus(ref, "项目约定", "CURRENT");
        when(contexts.project(eq(own), eq(6L), any())).thenReturn(List.of(context));
        var change = new DependencyChange("REQUEST_GOAL", null, "所需成果", "影响交付", "需求描述", "新目标", "影响");
        var fact = new TaskCollaborationDependencyService.DependencyFact("request-1", 1L, 7L, "b-1", 4L,
                LocalDateTime.of(2026, 11, 10, 8, 0), change, true, false, "GOAL_AUTHORIZATION_REQUIRED", null);
        when(dependencies.listFacts(6L, 101L)).thenReturn(List.of(fact));
        var goal = new GoalAuthorizationRequest("b-1", "request-1", "新目标", "影响", null);
        when(goals.project(eq(task), eq(7L), eq("b-1"), eq(List.of(own)), eq(List.of(fact)))).thenReturn(List.of(goal));
        when(batches.countAttempts("b-1")).thenReturn(1L);
        Detail detail = service.get(7L, new GetRequest(6L, "b-1"));
        assertEquals("WAITING_ACCEPT", detail.batch().displayState());
        assertEquals(1L, detail.batch().attemptsUsed());
        assertEquals(List.of(fact), detail.tasks().get(0).dependencyFacts());
        assertEquals(List.of(context), detail.tasks().get(0).contextFacts().get(0).refs());
        assertEquals(List.of(goal), detail.tasks().get(0).goalAuthorizationRequests());
        assertEquals("前置任务缺少成功成果", detail.tasks().get(0).inputsWaitingReason());
        assertEquals(List.of(1L), detail.tasks().get(0).attempts().stream().map(a -> a.id()).toList());
    }

    @Test void readModelSeparatesInputFactsFromActiveTaskAndHostArtifacts() {
        CollaborationBatch batch = batch("accepted", "RUN");
        Task task = task(101L, false);
        task.setStatus("in_progress");
        TaskExecutionAttempt sameTask = new TaskExecutionAttempt(); sameTask.setTaskId(101L); sameTask.setBindingId(11L);
        TaskExecutionAttempt competing = new TaskExecutionAttempt(); competing.setTaskId(102L); competing.setBindingId(10L);
        var prior = new TaskCollaborationInputService.PrerequisiteResult(99L, 44L, 3L, json.createObjectNode(), List.of());
        when(projects.lockProject(6L)).thenReturn(6L);
        when(batches.selectById("b-1")).thenReturn(batch);
        when(batches.selectTaskIds("b-1")).thenReturn(List.of(101L));
        when(tasks.selectById(101L)).thenReturn(task);
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of(prior)));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(sameTask, competing));
        TaskView view = service.get(7L, new GetRequest(6L, "b-1")).tasks().get(0);
        assertTrue(view.inputsReady());
        assertNull(view.inputsWaitingReason());
        assertEquals("in_progress", view.status());
        assertTrue(view.activeOrFrozen());
        assertTrue(view.prerequisiteReviewStatuses().get(0).adoptionRequired());
    }

    @Test void persistedReviewBlockRemainsWaitingInWebSummaryWithoutNewHostVerification() {
        CollaborationBatch batch = batch("accepted", "RUN");
        batch.setRunLeaseUntil(LocalDateTime.of(2026, 11, 10, 8, 0, 30));
        Task task = task(101L, false);
        task.setStatus("todo");
        when(projects.lockProject(6L)).thenReturn(6L);
        when(batches.selectById("b-1")).thenReturn(batch);
        when(batches.selectTaskIds("b-1")).thenReturn(List.of(101L));
        when(tasks.selectById(101L)).thenReturn(task);
        when(inputs.evaluateConfiguration(task, 4L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        when(reviews.blockingReason("b-1", task, List.of(), List.of())).thenReturn("等待相关成果变化");
        var result = service.get(7L, new GetRequest(6L, "b-1"));
        assertEquals("PREREQUISITE_REVIEW_WAIT", result.batch().displayState());
    }

    @Test void listingUsesOwnedCursorAndStableLastItemForNextPage() {
        when(projects.lockProject(6L)).thenReturn(6L);
        CollaborationBatch other = batch("queued", "RUN"); other.setUserId(8L);
        when(batches.selectById("other-cursor")).thenReturn(other);
        assertThrows(ResourceNotFoundException.class,
                () -> service.list(7L, new ListRequest(6L, 2, "other-cursor")));
        verify(batches, never()).selectPage(anyLong(), anyLong(), any(), any(), anyInt());

        CollaborationBatch one = batch("queued", "RUN"); one.setBatchId("b-3");
        CollaborationBatch two = batch("accepted", "RUN"); two.setBatchId("b-2");
        CollaborationBatch three = batch("ended", "STOP"); three.setBatchId("b-1");
        when(batches.selectPage(6L, 7L, null, null, 3)).thenReturn(List.of(one, two, three));
        Page first = service.list(7L, new ListRequest(6L, 2, null));
        assertTrue(first.hasMore());
        assertEquals("b-2", first.nextCursor());
        assertEquals(List.of("b-3", "b-2"), first.items().stream().map(Summary::batchId).toList());
        when(batches.selectById("b-2")).thenReturn(two);
        when(batches.selectPage(6L, 7L, two.getCreatedAt(), "b-2", 3)).thenReturn(List.of(three));
        Page next = service.list(7L, new ListRequest(6L, 2, "b-2"));
        assertFalse(next.hasMore());
        assertNull(next.nextCursor());
        assertEquals(List.of("b-1"), next.items().stream().map(Summary::batchId).toList());
    }

    @Test void recreatedServiceReadsBatchAndBudgetFromMappers() {
        CollaborationBatch stored = batch("accepted", "STOP");
        stored.setRunEpoch(3L);
        stored.setStartedAt(LocalDateTime.of(2026, 11, 10, 7, 59));
        stored.setDeadlineAt(LocalDateTime.of(2026, 11, 10, 8, 0));
        when(projects.lockProject(6L)).thenReturn(6L);
        when(batches.selectById("b-1")).thenReturn(stored);
        when(batches.selectTaskIds("b-1")).thenReturn(List.of());
        when(batches.countAttempts("b-1")).thenReturn(4L);
        Summary before = service.get(7L, new GetRequest(6L, "b-1")).batch();
        service = new CollaborationBatchControlService(batches, bindings, projects, tasks, attempts,
                access, hosts, inputs, operations, json, reviews, contexts, dependencies, goals, taskCommands, descriptions, clock);
        Summary after = service.get(7L, new GetRequest(6L, "b-1")).batch();
        assertEquals(before, after);
        assertEquals("STOP", after.desiredIntent());
        assertEquals(3L, after.runEpoch());
        assertEquals(4L, after.attemptsUsed());
        assertEquals(Instant.parse("2026-11-10T08:00:00Z"), after.deadlineAt());
        verify(batches, times(2)).selectById("b-1");
    }

    @Test void acceptedBatchWaitsForDependencyThenBecomesReadyWithoutResettingBudget() {
        CollaborationBatch batch = prepareAcceptedRead();
        Task task = tasks.selectById(101L);
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(false, "前置任务缺少成功成果", List.of()));
        assertEquals("DEPENDENCY_WAIT", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        Summary ready = service.get(7L, new GetRequest(6L, "b-1")).batch();
        assertEquals("READY", ready.displayState());
        assertEquals(3L, ready.attemptsUsed());
        assertEquals(batch.getDeadlineAt().toInstant(ZoneOffset.UTC), ready.deadlineAt());
        verify(batches, never()).updateById(any(CollaborationBatch.class));
        verifyNoInteractions(operations);
    }

    @Test void unapprovedGoalIsVisibleInBatchReadiness() {
        prepareAcceptedRead();
        when(dependencies.goalAuthorizationBlock(6L, 101L, "b-1", 4L)).thenReturn("新增业务目标等待授权");
        assertEquals("GOAL_AUTHORIZATION_WAIT", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
    }

    @Test void authorizingNewGoalCreatesOneTargetAndANewFixedBatch() {
        var candidate = prepareGoal(null, null, null);
        String goal = candidate.view().goal();
        String impact = candidate.view().impact();
        when(descriptions.convertTaskDescription(goal + "\n\n影响\n\n" + impact)).thenReturn("serialized description");
        Task target = task(200L, true);
        when(taskCommands.create(6L, 7L, null, goal, "serialized description", "todo", "medium",
                null, null, null, 0, List.of())).thenReturn(new TaskMutationResponse(target, List.of()));
        when(inputs.changeDependency(any(Task.class), eq(200L), eq(true), eq(7L))).thenReturn(true);

        var result = service.authorizeGoal(7L, new AuthorizeGoalRequest(6L, "b-1", "request-1"));

        var newBatch = ArgumentCaptor.forClass(CollaborationBatch.class);
        verify(batches).insert(newBatch.capture());
        assertEquals(result.path("batchId").asText(), newBatch.getValue().getBatchId());
        assertEquals(60000L, newBatch.getValue().getMaxDurationMs());
        assertEquals(1, newBatch.getValue().getMaxSlots());
        assertEquals(5, newBatch.getValue().getMaxAttempts());
        verify(batches).insertTask(result.path("batchId").asText(), 101L);
        verify(batches).insertTask(result.path("batchId").asText(), 200L);
        verify(inputs).changeDependency(any(Task.class), eq(200L), eq(true), eq(7L));
        verify(operations).save(eq(6L), eq(7L), eq(CollaborationGoalRequests.receiptId("request-1")),
                eq("goal_authorize"), eq(55L), any(), eq(result));
        verify(batches, never()).requestStop(eq("b-1"), any());
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
    }

    @Test void authorizedOutsideReworkReopensOnlyTheIdentifiedDelivery() {
        prepareGoal(201L, 88L, 3L);
        Task source = task(201L, true);
        source.setStatus("done");
        source.setInputRevision(3L);
        TaskExecutionAttempt delivery = new TaskExecutionAttempt();
        delivery.setId(88L); delivery.setInputRevision(3L);
        when(tasks.selectByIdForUpdate(201L)).thenReturn(source);
        when(inputs.currentDelivery(source, 10L)).thenReturn(delivery);
        when(inputs.hasDependency(101L, 201L)).thenReturn(true);

        var result = service.authorizeGoal(7L, new AuthorizeGoalRequest(6L, "b-1", "request-1"));

        verify(inputs).reopenForRework(6L, 201L, 7L);
        verify(batches).insertTask(result.path("batchId").asText(), 101L);
        verify(batches).insertTask(result.path("batchId").asText(), 201L);
        verifyNoInteractions(taskCommands);
        verify(inputs, never()).changeDependency(any(), anyLong(), anyBoolean(), anyLong());
    }

    @Test void staleOrForeignGoalCannotChangeTasksOrCreateBatch() {
        var candidate = prepareGoal(201L, 88L, 3L);
        Task source = task(201L, true); source.setStatus("done");
        TaskExecutionAttempt otherDelivery = new TaskExecutionAttempt();
        otherDelivery.setId(89L); otherDelivery.setInputRevision(3L);
        when(tasks.selectByIdForUpdate(201L)).thenReturn(source);
        when(inputs.currentDelivery(source, 10L)).thenReturn(otherDelivery);
        when(inputs.hasDependency(101L, 201L)).thenReturn(true);
        assertThrows(ConflictOperationException.class,
                () -> service.authorizeGoal(7L, new AuthorizeGoalRequest(6L, "b-1", "request-1")));
        verify(inputs, never()).reopenForRework(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(taskCommands);
        verify(batches, never()).insert(any(CollaborationBatch.class));

        CollaborationBatch foreign = batch("ended", "STOP"); foreign.setUserId(8L);
        when(batches.selectById("b-1")).thenReturn(foreign);
        assertThrows(ResourceNotFoundException.class,
                () -> service.authorizeGoal(7L, new AuthorizeGoalRequest(6L, "b-1", "request-1")));
        verify(batches, never()).insert(any(CollaborationBatch.class));
    }

    @Test void committedGoalAuthorizationReplayDoesNotRequireDeviceOnline() {
        var receipt = json.createObjectNode().put("batchId", "result-batch").put("lifecycle", "queued");
        when(operations.findCommittedReplay(eq(6L), eq(7L), eq(CollaborationGoalRequests.receiptId("request-1")),
                eq("goal_authorize"), any())).thenReturn(receipt);
        assertEquals(receipt, service.authorizeGoal(7L, new AuthorizeGoalRequest(6L, "b-1", "request-1")));
        verify(access).requireMember(6L, 7L);
        verifyNoInteractions(hosts, bindings, batches, taskCommands);
    }

    @Test void authorizationWaitsForOtherActiveAttemptsToStop() {
        prepareGoal(null, null, null);
        TaskExecutionAttempt active = new TaskExecutionAttempt(); active.setTaskId(101L);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(active));
        assertThrows(ConflictOperationException.class,
                () -> service.authorizeGoal(7L, new AuthorizeGoalRequest(6L, "b-1", "request-1")));
        verifyNoInteractions(taskCommands);
        verify(batches, never()).insert(any(CollaborationBatch.class));
    }

    private CollaborationGoalRequests.Candidate prepareGoal(Long sourceTaskId, Long sourceAttemptId, Long sourceRevision) {
        CollaborationBatch original = batch("ended", "STOP");
        when(batches.selectById("b-1")).thenReturn(original);
        when(batches.selectForUpdate("b-1")).thenReturn(original);
        when(bindings.selectById(10L)).thenReturn(binding(7L, 2L));
        when(bindings.selectForUpdate(7L, 6L, "device-A")).thenReturn(binding(7L, 2L));
        when(hosts.requireOnlineGrant(7L, "device-A")).thenReturn(grant());
        when(projects.lockProject(6L)).thenReturn(6L);
        Task task = task(101L, true);
        when(tasks.selectByIdForUpdate(101L)).thenReturn(task);
        when(inputs.evaluateConfiguration(task, 4L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        when(operations.findGoalRequestAttempt(6L, 7L, "request-1")).thenReturn(55L);
        TaskExecutionAttempt attempt = new TaskExecutionAttempt();
        attempt.setId(55L); attempt.setTaskId(101L); attempt.setBatchId("b-1");
        when(attempts.selectById(55L)).thenReturn(attempt);
        var goal = new GoalAuthorizationRequest("b-1", "request-1", "新增目标", "影响", null);
        var candidate = new CollaborationGoalRequests.Candidate(goal, 55L, 4L, sourceTaskId, sourceAttemptId, sourceRevision);
        when(goals.candidates(eq(task), eq(7L), eq("b-1"), eq(List.of(attempt)), any())).thenReturn(List.of(candidate));
        when(batches.insert(any(CollaborationBatch.class))).thenReturn(1);
        when(batches.insertTask(anyString(), anyLong())).thenReturn(1);
        return candidate;
    }

    @Test void otherTaskInSameBindingDoesNotBlockReadiness() {
        prepareAcceptedRead();
        Task task = tasks.selectById(101L);
        TaskExecutionAttempt owner = new TaskExecutionAttempt(); owner.setTaskId(102L); owner.setBatchId("other");
        owner.setBindingId(10L);
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(owner));
        assertEquals("READY", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of());
        assertEquals("READY", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
    }

    @Test void taskLeaseExpiryAndStopRemainVisibleWhileBatchLeaseIsValid() {
        CollaborationBatch batch = prepareAcceptedRead();
        TaskExecutionAttempt active = new TaskExecutionAttempt(); active.setTaskId(101L); active.setBatchId("b-1");
        active.setStatus("running"); active.setLeaseUntil(LocalDateTime.of(2026, 11, 10, 16, 0, 10));
        when(attempts.selectUnconfirmedByProject(6L)).thenReturn(List.of(active));
        assertEquals("RUNNING", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        active.setStatus("stop_requested");
        active.setStopRequestedAt(LocalDateTime.of(2026, 11, 10, 16, 0));
        assertEquals("STOPPING", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        active.setLeaseUntil(LocalDateTime.of(2026, 11, 10, 16, 0));
        assertEquals("RECOVERY", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        batch.setDesiredIntent("STOP");
        assertEquals("STOPPING", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        batch.setRunLeaseUntil(LocalDateTime.of(2026, 11, 10, 8, 0));
        assertEquals("RECOVERY", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
    }

    @Test void currentSuccessfulEvidenceDoesNotEndTheBatchBeforeHostFinish() {
        prepareAcceptedRead();
        Task task = tasks.selectById(101L); task.setStatus("done");
        TaskExecutionAttempt delivered = new TaskExecutionAttempt(); delivered.setBindingId(10L);
        delivered.setInputRevision(4L); delivered.setStatus("succeeded"); delivered.setBatchId("b-1");
        delivered.setStopConfirmedAt(LocalDateTime.of(2026, 11, 10, 7, 59));
        delivered.setResultEvidence(json.createObjectNode().put("result", "完成"));
        when(attempts.selectByTaskId(101L)).thenReturn(List.of(delivered));
        when(inputs.currentDelivery(task, 10L)).thenAnswer(invocation -> delivered.getInputRevision() == 4L ? delivered : null);
        when(batches.countAttempts("b-1")).thenReturn(5L);
        Summary result = service.get(7L, new GetRequest(6L, "b-1")).batch();
        assertEquals("READY", result.displayState());
        assertEquals("accepted", result.lifecycle());
        assertNull(result.endedAt());
        delivered.setInputRevision(3L);
        assertEquals("BUDGET_EXHAUSTED", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
        when(batches.countAttempts("b-1")).thenReturn(3L);
        assertEquals("BLOCKED", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
    }

    @Test void invalidConfigurationIsBlockedRatherThanACompletedOrDependencyWaitingBatch() {
        prepareAcceptedRead();
        Task task = tasks.selectById(101L);
        when(inputs.evaluateConfiguration(task, 4L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(false, "公共约定不可用或版本已变化", List.of()));
        assertEquals("BLOCKED", service.get(7L, new GetRequest(6L, "b-1")).batch().displayState());
    }

    private CollaborationBatch prepareAcceptedRead() {
        CollaborationBatch batch = batch("accepted", "RUN");
        batch.setRunLeaseUntil(LocalDateTime.of(2026, 11, 10, 8, 0, 30));
        batch.setDeadlineAt(LocalDateTime.of(2026, 11, 10, 8, 1));
        Task task = task(101L, true);
        when(projects.lockProject(6L)).thenReturn(6L);
        when(batches.selectById("b-1")).thenReturn(batch);
        when(batches.selectTaskIds("b-1")).thenReturn(List.of(101L));
        when(batches.countAttempts("b-1")).thenReturn(3L);
        when(tasks.selectById(101L)).thenReturn(task);
        when(inputs.evaluateConfiguration(task, 4L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        when(inputs.evaluateInputs(task, 4L, 10L)).thenReturn(
                new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        return batch;
    }

    private void prepareStart() {
        when(bindings.selectById(10L)).thenReturn(binding(7L, 2L));
        when(bindings.selectForUpdate(7L, 6L, "device-A")).thenReturn(binding(7L, 2L));
        when(hosts.requireOnlineGrant(7L, "device-A")).thenReturn(grant());
        when(projects.lockProject(6L)).thenReturn(6L);
        Task first = task(101L, true); Task second = task(102L, true);
        lenient().when(tasks.selectByIdForUpdate(101L)).thenReturn(first);
        lenient().when(tasks.selectByIdForUpdate(102L)).thenReturn(second);
        lenient().when(inputs.evaluateConfiguration(any(Task.class), eq(4L)))
                .thenReturn(new TaskCollaborationInputService.InputEvaluation(true, null, List.of()));
        lenient().when(batches.insert(any(CollaborationBatch.class))).thenReturn(1);
        lenient().when(batches.insertTask(anyString(), anyLong())).thenReturn(1);
    }

    private StartRequest startRequest() { return new StartRequest(6L, "start-1", 10L, 2L,
            List.of(102L, 101L), 60000L); }
    private ProjectDeviceBinding binding(Long user, Long revision) {
        ProjectDeviceBinding b = new ProjectDeviceBinding(); b.setId(10L); b.setUserId(user); b.setProjectId(6L);
        b.setDeviceId("device-A"); b.setProjectDirectory("/work/project"); b.setRevision(revision); return b;
    }
    private HostDeviceAuthorization grant() {
        HostDeviceAuthorization g = new HostDeviceAuthorization(); g.setId(30L); g.setUserId(7L); g.setDeviceId("device-A"); return g;
    }
    private Task task(Long id, boolean enabled) {
        Task t = new Task(); t.setId(id); t.setProjectId(6L); t.setTaskKey("TEST-" + id); t.setTitle("task");
        t.setStatus("todo"); t.setInputRevision(4L); return t;
    }
    private CollaborationBatch batch(String lifecycle, String intent) {
        CollaborationBatch b = new CollaborationBatch(); b.setBatchId("b-1"); b.setProjectId(6L); b.setUserId(7L);
        b.setBindingId(10L); b.setBindingRevision(2L); b.setHostAuthorizationId(30L); b.setMaxSlots(2);
        b.setMaxDurationMs(60000L); b.setMaxAttempts(5); b.setDesiredIntent(intent); b.setIntentRevision(1L);
        b.setLifecycle(lifecycle); b.setRunEpoch(0L); b.setCreatedAt(LocalDateTime.of(2026, 11, 10, 7, 55)); return b;
    }
}
