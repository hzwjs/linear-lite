package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CollaborationBatchDtos.*;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.dto.TaskCollaborationState.AttemptContext;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import com.linearlite.server.dto.CollaborationLeaseTime;
import com.linearlite.server.entity.CollaborationBatch;
import com.linearlite.server.entity.HostDeviceAuthorization;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.linearlite.server.dto.AttemptPrerequisiteReviews;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CollaborationBatchControlService {
    private static final String START = "batch_start";
    private static final String STOP = "batch_stop";
    private static final long INITIAL_LEASE_SECONDS = 30;
    // 与原网页默认一致；单批任务数沿用任务查询的 200 条上限。
    private static final long DEFAULT_SINGLE_DURATION_MS = 2L * 60 * 60 * 1000;
    private static final int DEFAULT_MAX_SLOTS = 1;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int MAX_TASKS = 200;
    private static final long MAX_DURATION_MS = Long.MAX_VALUE / 1_000_000L;
    private final CollaborationBatchMapper batches;
    private final ProjectDeviceBindingMapper bindings;
    private final ProjectMapper projects;
    private final TaskMapper tasks;
    private final TaskExecutionAttemptMapper attempts;
    private final ProjectAccessGuard access;
    private final HostDeviceAuthorizationService hosts;
    private final TaskCollaborationInputService inputs;
    private final TaskExecutionOperationService operations;
    private final ObjectMapper json;
    private final TaskPrerequisiteReviewService reviews;
    private final CollaborationContextService contexts;
    private final TaskCollaborationDependencyService dependencies;
    private final CollaborationGoalRequests goals;
    private final TaskCommandService taskCommands;
    private final MarkdownToBlockNoteConverter descriptions;
    private final Clock clock;

    @Autowired
    public CollaborationBatchControlService(CollaborationBatchMapper batches, ProjectDeviceBindingMapper bindings,
            ProjectMapper projects, TaskMapper tasks,
            TaskExecutionAttemptMapper attempts, ProjectAccessGuard access, HostDeviceAuthorizationService hosts,
            TaskCollaborationInputService inputs, TaskExecutionOperationService operations, ObjectMapper json, TaskPrerequisiteReviewService reviews,
            CollaborationContextService contexts, TaskCollaborationDependencyService dependencies,
            CollaborationGoalRequests goals, TaskCommandService taskCommands, MarkdownToBlockNoteConverter descriptions) {
        this(batches, bindings, projects, tasks, attempts, access, hosts, inputs, operations, json, reviews,
                contexts, dependencies, goals, taskCommands, descriptions, Clock.systemUTC());
    }

    CollaborationBatchControlService(CollaborationBatchMapper batches, ProjectDeviceBindingMapper bindings,
            ProjectMapper projects, TaskMapper tasks,
            TaskExecutionAttemptMapper attempts, ProjectAccessGuard access, HostDeviceAuthorizationService hosts,
            TaskCollaborationInputService inputs, TaskExecutionOperationService operations, ObjectMapper json, TaskPrerequisiteReviewService reviews,
            CollaborationContextService contexts, TaskCollaborationDependencyService dependencies,
            CollaborationGoalRequests goals, TaskCommandService taskCommands, MarkdownToBlockNoteConverter descriptions, Clock clock) {
        this.batches = batches;
        this.bindings = bindings;
        this.projects = projects;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.attempts = attempts;
        this.access = access;
        this.hosts = hosts;
        this.inputs = inputs;
        this.operations = operations;
        this.json = json;
        this.reviews = reviews;
        this.contexts = contexts;
        this.goals = goals;
        this.taskCommands = taskCommands;
        this.descriptions = descriptions;
        this.clock = clock;
    }

    /** 一张卡直接交给一个 Agent：等同于只含该任务的批次，使用默认运行时长，不经过任务选择和预算配置。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode startSingle(Long userId, StartSingleRequest request) {
        if (request == null || request.projectId() == null || request.projectId() <= 0
                || request.taskId() == null || request.taskId() <= 0
                || request.operationId() == null || request.operationId().isBlank())
            throw new IllegalArgumentException("单任务启动参数无效");
        access.requireMember(request.projectId(), userId);
        ProjectDeviceBinding binding;
        if (request.bindingId() != null) {
            binding = requireOwnedBinding(bindings.selectById(request.bindingId()), userId, request.projectId(), request.bindingId());
        } else {
            var online = bindings.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectDeviceBinding>()
                    .eq(ProjectDeviceBinding::getUserId, userId).eq(ProjectDeviceBinding::getProjectId, request.projectId())
                    .isNull(ProjectDeviceBinding::getDeletedAt)).stream()
                    .filter(item -> hosts.isOnlineDevice(userId, item.getDeviceId())).toList();
            if (online.isEmpty()) throw new ConflictOperationException("没有在线且已绑定本项目的设备");
            if (online.size() > 1) throw new ConflictOperationException("存在多台可用设备，请指定设备");
            binding = online.get(0);
        }
        return start(userId, new StartRequest(request.projectId(), request.operationId(), binding.getId(),
                binding.getRevision(), List.of(request.taskId()), DEFAULT_SINGLE_DURATION_MS));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode start(Long userId, StartRequest request) {
        validateStart(request);
        access.requireMember(request.projectId(), userId);
        JsonNode payload = json.valueToTree(request);
        JsonNode replay = operations.findCommittedReplay(request.projectId(), userId, request.operationId(), START, payload);
        if (replay != null) return replay;

        ProjectDeviceBinding observed = requireOwnedBinding(bindings.selectById(request.bindingId()),
                userId, request.projectId(), request.bindingId());
        // 授权锁先于项目锁；已提交回执的重放不依赖设备在线。
        HostDeviceAuthorization grant = hosts.requireOnlineGrant(userId, observed.getDeviceId());
        lockProject(request.projectId(), userId);
        replay = operations.findReplay(request.projectId(), userId, request.operationId(), START, payload);
        if (replay != null) return replay;
        ProjectDeviceBinding binding = lockBinding(userId, request.projectId(), observed.getDeviceId(),
                request.bindingId(), request.bindingRevision());

        List<Long> taskIds = request.taskIds().stream().sorted().toList();
        for (Long taskId : taskIds) {
            Task task = tasks.selectByIdForUpdate(taskId);
            if (task == null || !Objects.equals(task.getProjectId(), request.projectId()))
                throw new ResourceNotFoundException("批次任务不存在或不属于项目");
            if (!"todo".equalsIgnoreCase(task.getStatus()))
                throw new ConflictOperationException("任务状态不允许领取: " + task.getTaskKey());
            var valid = inputs.evaluateConfiguration(task, task.getInputRevision());
            if (!valid.ready()) throw new ConflictOperationException(valid.reason());
        }

        JsonNode result = createQueuedBatch(userId, binding, grant, taskIds, request.maxDurationMs());
        operations.save(request.projectId(), userId, request.operationId(), START, null, payload, result);
        return result;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode authorizeGoal(Long userId, AuthorizeGoalRequest request) {
        if (request == null) throw new IllegalArgumentException("目标授权请求不能为空");
        requireProject(request.projectId());
        requireBatchId(request.batchId());
        String operationId = CollaborationGoalRequests.receiptId(request.requestOperationId());
        String kind = CollaborationGoalRequests.RECEIPT_KIND;
        JsonNode payload = CollaborationGoalRequests.request(json, request.projectId(), request.batchId(), request.requestOperationId());
        access.requireMember(request.projectId(), userId);
        JsonNode replay = operations.findCommittedReplay(request.projectId(), userId, operationId, kind, payload);
        if (replay != null) return replay;

        CollaborationBatch original = requireOwnedBatch(batches.selectById(request.batchId()), userId, request.projectId());
        ProjectDeviceBinding observed = requireOwnedBinding(bindings.selectById(original.getBindingId()),
                userId, request.projectId(), original.getBindingId());
        // 与普通启动共用授权、项目、绑定的加锁顺序。
        HostDeviceAuthorization grant = hosts.requireOnlineGrant(userId, observed.getDeviceId());
        lockProject(request.projectId(), userId);
        replay = operations.findReplay(request.projectId(), userId, operationId, kind, payload);
        if (replay != null) return replay;
        original = requireOwnedBatch(batches.selectForUpdate(request.batchId()), userId, request.projectId());
        ProjectDeviceBinding binding = lockBinding(userId, request.projectId(), observed.getDeviceId(),
                original.getBindingId(), original.getBindingRevision());

        Long attemptId = operations.findGoalRequestAttempt(request.projectId(), userId, request.requestOperationId());
        TaskExecutionAttempt attempt = attemptId == null ? null : attempts.selectById(attemptId);
        if (attempt == null || !Objects.equals(attempt.getBatchId(), original.getBatchId()))
            throw new ResourceNotFoundException("业务目标授权请求不存在");
        Task task = requireProjectTaskForUpdate(request.projectId(), attempt.getTaskId());
        var facts = dependencies.listFacts(request.projectId(), task.getId());
        var goal = goals.candidates(task, userId, original.getBatchId(), List.of(attempt), facts).stream()
                .filter(candidate -> Objects.equals(candidate.view().requestOperationId(), request.requestOperationId()))
                .findFirst().orElseThrow(() -> new ConflictOperationException("业务目标授权请求已变化，请刷新"));
        var configuration = inputs.evaluateConfiguration(task, goal.inputRevision());
        if (!configuration.ready()) throw new ConflictOperationException(configuration.reason());
        if (attempts.selectUnconfirmedByProject(request.projectId()).stream()
                .anyMatch(active -> Objects.equals(active.getTaskId(), task.getId()) || Objects.equals(active.getTaskId(), goal.sourceTaskId())))
            throw new ConflictOperationException("任务等待停止确认");

        Long targetId;
        if (goal.sourceTaskId() != null) {
            Task source = requireProjectTaskForUpdate(request.projectId(), goal.sourceTaskId());
            TaskExecutionAttempt delivery = inputs.currentDelivery(source, binding.getId());
            if (!inputs.hasDependency(task.getId(), source.getId()) || delivery == null
                    || !Objects.equals(delivery.getId(), goal.sourceAttemptId())
                    || !Objects.equals(delivery.getInputRevision(), goal.sourceInputRevision()))
                throw new ConflictOperationException("来源交付已变化，请刷新");
            inputs.reopenForRework(request.projectId(), source.getId(), userId);
            targetId = source.getId();
        } else {
            String goalText = goal.view().goal().strip();
            String title = goalText.substring(0, goalText.offsetByCodePoints(0, Math.min(256, goalText.codePointCount(0, goalText.length()))));
            String description = descriptions.convertTaskDescription(goalText + "\n\n影响\n\n" + goal.view().impact());
            targetId = taskCommands.create(request.projectId(), userId, null, title, description,
                    "todo", "medium", null, null, null, 0, List.of()).task().getId();
            if (!inputs.changeDependency(task, targetId, true, userId))
                throw new IllegalStateException("新目标关系未建立");
        }
        List<Long> taskIds = List.of(task.getId(), targetId).stream().sorted().toList();
        JsonNode result = createQueuedBatch(userId, binding, grant, taskIds, original.getMaxDurationMs());
        operations.save(request.projectId(), userId, operationId, kind, attempt.getId(), payload, result);
        return result;
    }

    private Task requireProjectTaskForUpdate(Long projectId, Long taskId) {
        Task task = tasks.selectByIdForUpdate(taskId);
        if (task == null || !Objects.equals(task.getProjectId(), projectId))
            throw new ResourceNotFoundException("任务不存在或不属于项目");
        return task;
    }

    private JsonNode createQueuedBatch(Long userId, ProjectDeviceBinding binding, HostDeviceAuthorization grant,
                                       List<Long> taskIds, Long duration) {
        CollaborationBatch batch = new CollaborationBatch();
        batch.setBatchId(UUID.randomUUID().toString());
        batch.setUserId(userId);
        batch.setProjectId(binding.getProjectId());
        batch.setBindingId(binding.getId());
        batch.setBindingRevision(binding.getRevision());
        batch.setHostAuthorizationId(grant.getId());
        batch.setMaxSlots(DEFAULT_MAX_SLOTS);
        batch.setMaxDurationMs(duration);
        batch.setMaxAttempts(Math.max(DEFAULT_MAX_ATTEMPTS, taskIds.size()));
        batch.setDesiredIntent("RUN");
        batch.setIntentRevision(1L);
        batch.setLifecycle("queued");
        batch.setRunEpoch(0L);
        batch.setCreatedAt(now());
        if (batches.insert(batch) != 1) throw new IllegalStateException("批次保存失败");
        for (Long taskId : taskIds) {
            if (batches.insertTask(batch.getBatchId(), taskId) != 1) throw new IllegalStateException("批次任务保存失败");
        }
        return json.valueToTree(commandResult(batch));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode stop(Long userId, StopRequest request) {
        if (request == null) throw new IllegalArgumentException("停止请求不能为空");
        requireProject(request.projectId());
        requireBatchId(request.batchId());
        access.requireMember(request.projectId(), userId);
        JsonNode payload = json.valueToTree(request);
        JsonNode replay = operations.findCommittedReplay(request.projectId(), userId, request.operationId(), STOP, payload);
        if (replay != null) return replay;
        lockProject(request.projectId(), userId);
        replay = operations.findReplay(request.projectId(), userId, request.operationId(), STOP, payload);
        if (replay != null) return replay;
        CollaborationBatch batch = requireOwnedBatch(batches.selectForUpdate(request.batchId()), userId, request.projectId());
        if ("RUN".equals(batch.getDesiredIntent()) && !"ended".equals(batch.getLifecycle())) {
            if (batches.requestStop(batch.getBatchId(), now()) != 1) throw new ConflictOperationException("批次状态发生变化");
            batch = requireOwnedBatch(batches.selectForUpdate(request.batchId()), userId, request.projectId());
        }
        JsonNode result = json.valueToTree(commandResult(batch));
        operations.save(request.projectId(), userId, request.operationId(), STOP, null, payload, result);
        return result;
    }

    /** 132 的宿主执行门禁接入前不暴露此入口，也不签发批次执行凭据。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean acceptQueued(Long userId, Long projectId, String batchId, Long grantId, String hostInstanceId) {
        requireProject(projectId);
        requireBatchId(batchId);
        if (grantId == null || grantId <= 0 || hostInstanceId == null || hostInstanceId.isBlank()
                || hostInstanceId.length() > 128) throw new IllegalArgumentException("宿主领取参数无效");
        access.requireMember(projectId, userId);
        CollaborationBatch observed = requireOwnedBatch(batches.selectById(batchId), userId, projectId);
        ProjectDeviceBinding oldBinding = requireOwnedBinding(bindings.selectById(observed.getBindingId()),
                userId, projectId, observed.getBindingId());
        HostDeviceAuthorization grant = hosts.requireOnlineGrant(userId, oldBinding.getDeviceId());
        if (!Objects.equals(grant.getId(), grantId)) throw new ConflictOperationException("宿主授权与批次不匹配");
        lockProject(projectId, userId);
        lockBinding(userId, projectId, oldBinding.getDeviceId(), observed.getBindingId(), observed.getBindingRevision());
        CollaborationBatch batch = requireOwnedBatch(batches.selectForUpdate(batchId), userId, projectId);
        if (!Objects.equals(batch.getHostAuthorizationId(), grantId))
            throw new ConflictOperationException("宿主授权与批次不匹配");
        if (!"queued".equals(batch.getLifecycle()) || !"RUN".equals(batch.getDesiredIntent())) return false;
        LocalDateTime now = now();
        LocalDateTime deadline;
        try { deadline = now.plusNanos(Math.multiplyExact(batch.getMaxDurationMs(), 1_000_000L)); }
        catch (ArithmeticException | DateTimeException e) { throw new IllegalArgumentException("运行时长超出范围"); }
        LocalDateTime leaseUntil = now.plusSeconds(INITIAL_LEASE_SECONDS);
        if (deadline.isBefore(leaseUntil)) leaseUntil = deadline;
        return batches.acceptQueued(batchId, hostInstanceId, leaseUntil, now, deadline) == 1;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Page list(Long userId, ListRequest request) {
        if (request == null) throw new IllegalArgumentException("查询请求不能为空");
        requireProject(request.projectId());
        if (request.limit() == null || request.limit() < 1 || request.limit() > 100)
            throw new IllegalArgumentException("分页大小须为 1 至 100");
        lockProject(request.projectId(), userId);
        CollaborationBatch before = null;
        if (request.beforeBatchId() != null) {
            requireBatchId(request.beforeBatchId());
            before = requireOwnedBatch(batches.selectById(request.beforeBatchId()), userId, request.projectId());
        }
        List<CollaborationBatch> rows = batches.selectPage(request.projectId(), userId,
                before == null ? null : before.getCreatedAt(), before == null ? null : before.getBatchId(), request.limit() + 1);
        boolean hasMore = rows.size() > request.limit();
        List<CollaborationBatch> page = hasMore ? rows.subList(0, request.limit()) : rows;
        String nextCursor = hasMore ? page.get(page.size() - 1).getBatchId() : null;
        return new Page(page.stream().map(this::summary).toList(), hasMore, nextCursor);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Detail get(Long userId, GetRequest request) {
        if (request == null) throw new IllegalArgumentException("查询请求不能为空");
        requireProject(request.projectId());
        requireBatchId(request.batchId());
        lockProject(request.projectId(), userId);
        CollaborationBatch batch = requireOwnedBatch(batches.selectById(request.batchId()), userId, request.projectId());
        List<TaskExecutionAttempt> pending = attempts.selectUnconfirmedByProject(request.projectId());
        List<TaskView> views = batches.selectTaskIds(batch.getBatchId()).stream()
                .map(id -> taskView(batch, id, pending)).toList();
        return new Detail(clock.instant(), summary(batch), views);
    }

    private TaskView taskView(CollaborationBatch batch, Long taskId, List<TaskExecutionAttempt> pending) {
        Task task = tasks.selectById(taskId);
        if (task == null || !Objects.equals(task.getProjectId(), batch.getProjectId()))
            return new TaskView(taskId, null, null, null, null, false,
                    "任务不存在或不属于项目", false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var evaluation = inputs.evaluateInputs(task, task.getInputRevision(), batch.getBindingId());
        boolean activeOrFrozen = pending.stream().anyMatch(a -> Objects.equals(a.getTaskId(), taskId));
        var dependencyFacts = dependencies.listFacts(batch.getProjectId(), taskId);
        List<TaskExecutionAttempt> taskHistory = attempts.selectByTaskId(taskId);
        List<TaskExecutionAttempt> batchHistory = taskHistory.stream()
                .filter(a -> Objects.equals(a.getBatchId(), batch.getBatchId())).toList();
        return new TaskView(taskId, task.getTaskKey(), task.getTitle(), task.getStatus(),
                task.getInputRevision(), evaluation.ready(), evaluation.reason(), activeOrFrozen, evaluation.prerequisites(),
                batchHistory.stream().map(TaskExecutionAttemptResponse::from).toList(),
                batchHistory.stream().map(a -> new AttemptPrerequisiteReviews(a.getId(), reviews.project(a))).toList(),
                TaskPrerequisiteReviewService.projectStatuses(task, batch.getBindingId(), evaluation.prerequisites(), taskHistory, List.of()),
                batchHistory.stream().map(a -> new AttemptContext(a.getId(), contexts.project(a, task.getProjectId(), Map.of()))).toList(),
                dependencyFacts, goals.project(task, batch.getUserId(), batch.getBatchId(), batchHistory, dependencyFacts));
    }

    private Summary summary(CollaborationBatch b) {
        return new Summary(b.getBatchId(), b.getProjectId(), b.getUserId(), b.getBindingId(), b.getBindingRevision(),
                b.getHostAuthorizationId(), b.getMaxSlots(), b.getMaxDurationMs(), b.getMaxAttempts(),
                b.getDesiredIntent(), b.getIntentRevision(), b.getLifecycle(), b.getHostInstanceId(), b.getRunEpoch(),
                instant(b.getRunLeaseUntil()), instant(b.getStartedAt()), instant(b.getDeadlineAt()), instant(b.getEndedAt()),
                b.getEndReason(), instant(b.getCreatedAt()), batches.countAttempts(b.getBatchId()), displayState(b));
    }

    private CommandResult commandResult(CollaborationBatch batch) {
        return new CommandResult(batch.getBatchId(), batch.getLifecycle(), batch.getDesiredIntent(),
                batch.getIntentRevision(), displayState(batch));
    }

    private String displayState(CollaborationBatch batch) {
        if ("ended".equals(batch.getLifecycle())) return "ENDED";
        if ("queued".equals(batch.getLifecycle())) return "WAITING_ACCEPT";
        if (batch.getRunLeaseUntil() == null || !batch.getRunLeaseUntil().isAfter(now())) return "RECOVERY";
        if ("STOP".equals(batch.getDesiredIntent())) return "STOPPING";
        // 查询只投影持久事实，不调用会触发停止写入的宿主观察流程。
        List<TaskExecutionAttempt> pending = attempts.selectUnconfirmedByProject(batch.getProjectId());
        List<TaskExecutionAttempt> owned = pending.stream()
                .filter(a -> Objects.equals(a.getBatchId(), batch.getBatchId())).toList();
        if (owned.stream().anyMatch(a -> a.getLeaseUntil() == null
                || !CollaborationLeaseTime.toInstant(a.getLeaseUntil()).isAfter(clock.instant())))
            return "RECOVERY";
        if (owned.stream().anyMatch(a -> "stop_requested".equals(a.getStatus())
                || ("running".equals(a.getStatus()) && a.getStopRequestedAt() != null))) return "STOPPING";
        if (owned.stream().anyMatch(a -> !"running".equals(a.getStatus()))) return "RECOVERY";
        if (!owned.isEmpty()) return "RUNNING";
        List<String> states = batches.selectTaskIds(batch.getBatchId()).stream()
                .map(taskId -> readiness(batch, tasks.selectById(taskId), pending)).toList();
        // 成果齐备仍须等待宿主 finish，耗尽预算不代表任务未完成。
        if (!states.isEmpty() && states.stream().allMatch("COMPLETE"::equals)) return "READY";
        if ((batch.getDeadlineAt() != null && !batch.getDeadlineAt().isAfter(now()))
                || batches.countAttempts(batch.getBatchId()) >= batch.getMaxAttempts()) return "BUDGET_EXHAUSTED";
        if (states.contains("READY")) return "READY";
        if (states.contains("STOPPING_WAIT")) return "STOPPING_WAIT";
        if (states.contains("TASK_WAIT")) return "TASK_WAIT";
        if (states.contains("DEPENDENCY_WAIT")) return "DEPENDENCY_WAIT";
        if (states.contains("GOAL_AUTHORIZATION_WAIT")) return "GOAL_AUTHORIZATION_WAIT";
        if (states.contains("PREREQUISITE_REVIEW_WAIT")) return "PREREQUISITE_REVIEW_WAIT";
        return "BLOCKED";
    }

    private String readiness(CollaborationBatch batch, Task task, List<TaskExecutionAttempt> pending) {
        if (task == null || !Objects.equals(task.getProjectId(), batch.getProjectId())) return "BLOCKED";
        List<TaskExecutionAttempt> taskPending = pending.stream().filter(a -> Objects.equals(a.getTaskId(), task.getId())).toList();
        if (taskPending.stream().anyMatch(a -> a.getStopRequestedAt() != null || !"running".equals(a.getStatus()))) return "STOPPING_WAIT";
        if (!taskPending.isEmpty()) return "TASK_WAIT";
        if (inputs.splitParent(task)) return "done".equalsIgnoreCase(task.getStatus()) ? "COMPLETE" : "DEPENDENCY_WAIT";
        if ("done".equalsIgnoreCase(task.getStatus())) {
            return inputs.currentDelivery(task, batch.getBindingId()) == null ? "BLOCKED" : "COMPLETE";
        }
        if (dependencies.goalAuthorizationBlock(batch.getProjectId(), task.getId(), batch.getBatchId(), task.getInputRevision()) != null)
            return "GOAL_AUTHORIZATION_WAIT";
        if (!"todo".equalsIgnoreCase(task.getStatus()))
            return "BLOCKED";
        if (!inputs.evaluateConfiguration(task, task.getInputRevision()).ready()) return "BLOCKED";
        var input = inputs.evaluateInputs(task, task.getInputRevision(), batch.getBindingId());
        if (!input.ready()) return "DEPENDENCY_WAIT";
        if (reviews.blockingReason(batch.getBatchId(), task, input.prerequisites(), List.of()) != null)
            return "PREREQUISITE_REVIEW_WAIT";
        // 已有有效交付的本机材料列为待复核，不作为启动或领取门槛。
        return "READY";
    }

    private void validateStart(StartRequest request) {
        if (request == null) throw new IllegalArgumentException("启动请求不能为空");
        requireProject(request.projectId());
        if (request.bindingId() == null || request.bindingId() <= 0 || request.bindingRevision() == null
                || request.bindingRevision() <= 0 || request.maxDurationMs() == null || request.maxDurationMs() < 1
                || request.maxDurationMs() > MAX_DURATION_MS || request.taskIds() == null
                || request.taskIds().isEmpty() || request.taskIds().size() > MAX_TASKS || request.taskIds().stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(request.taskIds()).size() != request.taskIds().size()) {
            throw new IllegalArgumentException("批次设备绑定、所选任务或运行时长无效");
        }
        try { Math.multiplyExact(request.maxDurationMs(), 1_000_000L); clock.instant().plusMillis(request.maxDurationMs()); }
        catch (ArithmeticException | DateTimeException e) { throw new IllegalArgumentException("运行时长超出范围"); }
    }

    private void lockProject(Long projectId, Long userId) {
        access.requireMember(projectId, userId);
        if (projects.lockProject(projectId) == null) throw new ResourceNotFoundException("项目不存在");
        access.requireMember(projectId, userId);
    }

    private ProjectDeviceBinding lockBinding(Long userId, Long projectId, String deviceId, Long bindingId, Long revision) {
        ProjectDeviceBinding binding = requireOwnedBinding(bindings.selectForUpdate(userId, projectId, deviceId),
                userId, projectId, bindingId);
        if (!Objects.equals(binding.getDeviceId(), deviceId) || !Objects.equals(binding.getRevision(), revision))
            throw new ConflictOperationException("项目目录绑定版本已变化");
        return binding;
    }

    private ProjectDeviceBinding requireOwnedBinding(ProjectDeviceBinding binding, Long userId, Long projectId, Long bindingId) {
        if (binding == null || binding.getDeletedAt() != null || !Objects.equals(binding.getId(), bindingId)
                || !Objects.equals(binding.getUserId(), userId) || !Objects.equals(binding.getProjectId(), projectId))
            throw new ResourceNotFoundException("项目设备绑定不存在");
        return binding;
    }

    private CollaborationBatch requireOwnedBatch(CollaborationBatch batch, Long userId, Long projectId) {
        if (batch == null || !Objects.equals(batch.getUserId(), userId) || !Objects.equals(batch.getProjectId(), projectId))
            throw new ResourceNotFoundException("批次不存在");
        return batch;
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) throw new IllegalArgumentException("项目标识无效");
    }

    private void requireBatchId(String batchId) {
        if (batchId == null || batchId.isBlank() || batchId.length() > 36) throw new IllegalArgumentException("批次标识无效");
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private Instant instant(LocalDateTime time) { return time == null ? null : time.toInstant(ZoneOffset.UTC); }
}
