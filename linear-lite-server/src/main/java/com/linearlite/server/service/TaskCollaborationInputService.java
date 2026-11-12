package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.ArtifactObservation;
import com.linearlite.server.dto.TaskCollaborationInputs;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskDependency;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskDependencyMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class TaskCollaborationInputService {
    static final String TRACE_PENDING = "前置成果等待本机核验";
    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final TaskDependencyMapper dependencyMapper;
    private final TaskExecutionAttemptMapper attemptMapper;
    private final TaskExecutionOperationMapper operationMapper;
    private final CollaborationBatchMapper batchMapper;
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDeviceBindingMapper bindingMapper;
    private final TaskPermissionGuard permissionGuard;
    private final ProjectAccessGuard accessGuard;
    private final TaskStatusService taskStatusService;
    private final ObjectMapper objectMapper;

    public TaskCollaborationInputService(ProjectMapper projectMapper, TaskMapper taskMapper,
                                         TaskDependencyMapper dependencyMapper, TaskExecutionAttemptMapper attemptMapper,
                                         ProjectDocumentMapper documentMapper, ProjectDeviceBindingMapper bindingMapper,
                                         TaskPermissionGuard permissionGuard, ProjectAccessGuard accessGuard,
                                         TaskStatusService taskStatusService, ObjectMapper objectMapper,
                                         TaskExecutionOperationMapper operationMapper,
                                         CollaborationBatchMapper batchMapper) {
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.dependencyMapper = dependencyMapper;
        this.attemptMapper = attemptMapper;
        this.operationMapper = operationMapper;
        this.batchMapper = batchMapper;
        this.documentMapper = documentMapper;
        this.bindingMapper = bindingMapper;
        this.permissionGuard = permissionGuard;
        this.accessGuard = accessGuard;
        this.taskStatusService = taskStatusService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<TaskDependency> listDependencies(String taskKey, Long userId) {
        Task task = permissionGuard.requireTaskAccessByKey(taskKey, userId);
        return dependencyMapper.selectByTaskId(task.getId());
    }

    /** 项目协调锁保证任务输入与依赖来自同一时点。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskCollaborationInputs getInputs(String taskKey, Long userId) {
        Task observed = permissionGuard.requireTaskAccessByKey(taskKey, userId);
        requireProjectLock(observed.getProjectId());
        Task current = requireTaskForUpdate(observed.getId(), observed.getProjectId());
        permissionGuard.requireProjectMember(current.getProjectId(), userId);
        List<Long> prerequisites = dependencyMapper.selectByTaskId(current.getId()).stream()
                .map(TaskDependency::getPrerequisiteTaskId).sorted().toList();
        return new TaskCollaborationInputs(current.getInputRevision(), prerequisites);
    }

    /** 交付入口在项目事务内调用该方法；领取与交付操作由后续服务承担。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrentInputs(Long taskId, Long expectedRevision, Long bindingId, Long userId) {
        Task current = taskMapper.selectById(taskId);
        if (current == null) throw new ResourceNotFoundException("任务不存在: " + taskId);
        permissionGuard.requireProjectMember(current.getProjectId(), userId);
        requireProjectLock(current.getProjectId());
        current = requireTaskForUpdate(current.getId(), current.getProjectId());
        InputEvaluation evaluation = evaluateInputs(current, expectedRevision, bindingId);
        if (!evaluation.ready()) throw new ConflictOperationException(evaluation.reason());
    }

    /** 配置有效性与前置成果就绪分开；项目锁由调用方持有。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InputEvaluation evaluateConfiguration(Task current, Long expectedRevision) {
        if (expectedRevision == null || !Objects.equals(current.getInputRevision(), expectedRevision))
            return new InputEvaluation(false, "任务输入版本已变化", List.of());
        Long children = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getParentId, current.getId()));
        if (children == null) throw new IllegalStateException("子任务数量查询失败");
        if (children > 0) return new InputEvaluation(false, "协作执行要求叶子任务", List.of());
        for (TaskDependency edge : dependencyMapper.selectByTaskId(current.getId())) {
            Task prerequisite = taskMapper.selectById(edge.getPrerequisiteTaskId());
            if (prerequisite == null || !Objects.equals(prerequisite.getProjectId(), current.getProjectId()))
                return new InputEvaluation(false, "前置任务必须属于同一项目", List.of());
            Long prerequisiteChildren = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getParentId, prerequisite.getId()));
            if (prerequisiteChildren == null) throw new IllegalStateException("子任务数量查询失败");
            if (prerequisiteChildren > 0) return new InputEvaluation(false, "前置任务不是叶子任务", List.of());
        }
        return new InputEvaluation(true, null, List.of());
    }

    /** 项目锁由调用方持有；不以异常表达可预期的就绪阻塞。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InputEvaluation evaluateInputs(Task current, Long expectedRevision, Long bindingId) {
        return evaluateInputs(current, expectedRevision, bindingId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InputEvaluation evaluateInputs(Task current, Long expectedRevision, Long bindingId, String batchId) {
        InputEvaluation configuration = evaluateConfiguration(current, expectedRevision);
        if (!configuration.ready()) return configuration;
        List<PrerequisiteResult> results = new ArrayList<>();
        boolean needsObservation = false;
        for (TaskDependency edge : dependencyMapper.selectByTaskId(current.getId())) {
            Task prerequisite = taskMapper.selectById(edge.getPrerequisiteTaskId());
            if (prerequisite == null || !Objects.equals(prerequisite.getProjectId(), current.getProjectId()))
                return new InputEvaluation(false, "前置任务缺少成果", results);
            TaskExecutionAttempt delivered = currentDelivery(prerequisite, bindingId);
            PrerequisiteResult trace = currentTrace(prerequisite, bindingId,
                    batchId == null ? Set.of() : waitingPaths(current, prerequisite.getId(), batchId));
            if (trace != null) {
                results.add(trace);
                needsObservation = true;
                continue;
            }
            if (delivered != null && delivered.getResultEvidence().path("artifactRefs").isArray()
                    && delivered.getResultEvidence().path("artifactRefs").isEmpty()) {
                if (!delivered.getResultEvidence().isObject())
                    return new InputEvaluation(false, "前置成果记录无效", results);
                ObjectNode evidence = ((ObjectNode) delivered.getResultEvidence()).deepCopy();
                evidence.set("artifactObservations", objectMapper.createArrayNode());
                evidence.set("traceOperationIds", objectMapper.createObjectNode());
                results.add(new PrerequisiteResult(prerequisite.getId(), delivered.getId(), delivered.getInputRevision(),
                        evidence, List.of()));
                continue;
            }
            return new InputEvaluation(false, "前置任务缺少可核验成果", results);
        }
        return needsObservation ? new InputEvaluation(false, TRACE_PENDING, results)
                : new InputEvaluation(true, null, results);
    }

    PrerequisiteResult currentTrace(Task source, Long bindingId) {
        return currentTrace(source, bindingId, Set.of());
    }

    private Set<String> waitingPaths(Task current, Long sourceId, String batchId) {
        List<TaskExecutionAttempt> history = attemptMapper.selectByTaskId(current.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            TaskExecutionAttempt attempt = history.get(i);
            if (!Objects.equals(attempt.getBatchId(), batchId) || !Objects.equals(attempt.getInputRevision(), current.getInputRevision())
                    || attempt.getCheckpoints() == null || !attempt.getCheckpoints().isArray()) continue;
            JsonNode points = attempt.getCheckpoints();
            for (int j = points.size() - 1; j >= 0; j--) {
                JsonNode point = points.get(j);
                JsonNode review = point.path("review");
                if (!"PREREQUISITE_REVIEW".equals(point.path("kind").asText())
                        || review.path("sourceTaskId").asLong() != sourceId) continue;
                if (!"WAIT_FOR_CHANGE".equals(point.path("outcome").asText())) return Set.of();
                Set<String> paths = new LinkedHashSet<>();
                for (JsonNode observation : review.path("observations")) {
                    String path = observation.path("path").asText();
                    if (!path.isBlank()) paths.add(path);
                }
                return paths;
            }
        }
        return Set.of();
    }

    private PrerequisiteResult currentTrace(Task source, Long bindingId, Set<String> waitingPaths) {
        if ("canceled".equalsIgnoreCase(source.getStatus()) || "duplicate".equalsIgnoreCase(source.getStatus())) return null;
        ProjectDeviceBinding binding = bindingMapper.selectById(bindingId);
        if (binding == null || binding.getDeletedAt() != null || !Objects.equals(binding.getProjectId(), source.getProjectId())) return null;
        List<TaskExecutionAttempt> history = attemptMapper.selectByTaskId(source.getId());
        List<TraceCandidate> candidates = new ArrayList<>();
        List<TraceCandidate> relatedCandidates = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            TaskExecutionAttempt attempt = history.get(i);
            if (!validTraceOwner(source, binding, attempt) || attempt.getCheckpoints() == null || !attempt.getCheckpoints().isArray()) continue;
            JsonNode checkpoints = attempt.getCheckpoints();
            Map<String, JsonNode> traces = new LinkedHashMap<>();
            for (int j = checkpoints.size() - 1; j >= 0; j--) {
                JsonNode checkpoint = checkpoints.get(j);
                JsonNode observations = checkpoint.path("artifactObservations");
                JsonNode refs = checkpoint.path("artifactRefs");
                String operationId = checkpoint.path("operationId").asText("");
                if (!observations.isArray() || observations.size() != 1 || !refs.isArray() || refs.size() != 1 || operationId.isBlank()) continue;
                try {
                    ArtifactObservation observation = objectMapper.treeToValue(observations.get(0), ArtifactObservation.class);
                    TaskPrerequisiteReviewService.validateObservations(List.of(observation));
                    if (!"PRESENT".equals(observation.state()) || observation.path().endsWith("/")
                            || !Objects.equals(observation.path(), refs.get(0).asText())) continue;
                    if (!seenPaths.contains(observation.path())) traces.putIfAbsent(observation.path(), checkpoint);
                } catch (Exception ignored) {
                    // 损坏的历史事实不构成可用材料。
                }
            }
            if (!traces.isEmpty()) {
                seenPaths.addAll(traces.keySet());
                ObjectNode evidence = objectMapper.createObjectNode();
                var refs = evidence.putArray("artifactRefs");
                var observations = evidence.putArray("artifactObservations");
                ObjectNode operationIds = evidence.putObject("traceOperationIds");
                traces.forEach((path, point) -> {
                    refs.add(path);
                    observations.add(point.path("artifactObservations").get(0).deepCopy());
                    operationIds.put(path, point.path("operationId").asText());
                });
                TraceCandidate candidate = new TraceCandidate(attempt.getId(), attempt.getInputRevision(), evidence);
                if (!waitingPaths.isEmpty() && traces.keySet().stream().anyMatch(waitingPaths::contains)) relatedCandidates.add(candidate);
                else candidates.add(candidate);
            }
        }
        relatedCandidates.addAll(candidates);
        candidates = relatedCandidates;
        if (candidates.isEmpty()) return null;
        TraceCandidate selected = candidates.get(0);
        return new PrerequisiteResult(source.getId(), selected.attemptId(), selected.inputRevision(), selected.evidence(), List.copyOf(candidates));
    }

    private boolean validTraceOwner(Task source, ProjectDeviceBinding binding, TaskExecutionAttempt attempt) {
        if ("canceled".equalsIgnoreCase(source.getStatus()) || "duplicate".equalsIgnoreCase(source.getStatus())
                || attempt == null || !Objects.equals(attempt.getTaskId(), source.getId())
                || !Objects.equals(attempt.getBindingId(), binding.getId())
                || !Objects.equals(attempt.getInputRevision(), source.getInputRevision())) return false;
        boolean running = ("running".equals(attempt.getStatus()) || "stop_requested".equals(attempt.getStatus()))
                && attempt.getStopConfirmedAt() == null;
        boolean stopped = attempt.getStopConfirmedAt() != null
                && ("stopped".equals(attempt.getStatus()) || "succeeded".equals(attempt.getStatus()));
        if (!running && !stopped) return false;
        var sourceBatch = batchMapper.selectById(attempt.getBatchId());
        return sourceBatch != null && Objects.equals(sourceBatch.getProjectId(), source.getProjectId())
                && Objects.equals(sourceBatch.getBindingId(), binding.getId())
                && Objects.equals(sourceBatch.getBindingRevision(), binding.getRevision());
    }

    /** 领取快照引用的来源在后续追加新痕迹或完成交付时保持有效。 */
    boolean matchesPrerequisiteSnapshot(Task current, TaskExecutionAttempt attempt) {
        if (!evaluateConfiguration(current, attempt.getInputRevision()).ready()) return false;
        JsonNode snapshot = attempt.getPrerequisiteVersions();
        List<TaskDependency> edges = dependencyMapper.selectByTaskId(current.getId());
        if (snapshot == null || !snapshot.isArray() || snapshot.size() != edges.size()) return false;
        for (TaskDependency edge : edges) {
            JsonNode saved = null;
            for (JsonNode item : snapshot) if (item.path("taskId").asLong() == edge.getPrerequisiteTaskId()) {
                if (saved != null) return false;
                saved = item;
            }
            if (saved == null) return false;
            Task source = taskMapper.selectById(edge.getPrerequisiteTaskId());
            TaskExecutionAttempt owner = attemptMapper.selectById(saved.path("attemptId").asLong());
            if (source == null || owner == null || !Objects.equals(source.getProjectId(), current.getProjectId())
                    || !Objects.equals(owner.getInputRevision(), source.getInputRevision())
                    || !Objects.equals(owner.getInputRevision(), saved.path("inputRevision").longValue())) return false;
            JsonNode evidence = saved.path("evidence");
            if (!evidence.path("artifactObservations").isArray() || evidence.path("artifactObservations").isEmpty()) {
                ProjectDeviceBinding binding = bindingMapper.selectById(attempt.getBindingId());
                if ("canceled".equalsIgnoreCase(source.getStatus()) || "duplicate".equalsIgnoreCase(source.getStatus())
                        || !Objects.equals(owner.getTaskId(), source.getId())
                        || !Objects.equals(owner.getBindingId(), attempt.getBindingId())
                        || !"succeeded".equals(owner.getStatus()) || owner.getStopConfirmedAt() == null
                        || owner.getResultEvidence() == null || owner.getResultEvidence().isNull()
                        || binding == null || binding.getDeletedAt() != null || !validTraceOwner(source, binding, owner)) return false;
                continue;
            }
            ProjectDeviceBinding binding = bindingMapper.selectById(attempt.getBindingId());
            if (binding == null || binding.getDeletedAt() != null || !validTraceOwner(source, binding, owner)
                    || owner.getCheckpoints() == null || !owner.getCheckpoints().isArray()) return false;
            for (JsonNode observed : evidence.path("artifactObservations")) {
                String path = observed.path("path").asText();
                String operationId = evidence.path("traceOperationIds").path(path).asText();
                boolean found = false;
                for (JsonNode point : owner.getCheckpoints()) {
                    if (operationId.equals(point.path("operationId").asText())
                            && point.path("artifactObservations").isArray() && point.path("artifactObservations").size() == 1
                            && observed.equals(point.path("artifactObservations").get(0))) found = true;
                }
                if (operationId.isBlank() || !found) return false;
            }
        }
        return true;
    }

    TaskExecutionAttempt currentDelivery(Task task, Long bindingId) {
        if (!"done".equalsIgnoreCase(task.getStatus())) return null;
        ProjectDeviceBinding binding = bindingMapper.selectById(bindingId);
        if (binding == null || binding.getDeletedAt() != null || !Objects.equals(binding.getProjectId(), task.getProjectId())) return null;
        return attemptMapper.selectByTaskId(task.getId()).stream()
                .filter(attempt -> Objects.equals(bindingId, attempt.getBindingId())
                        && Objects.equals(task.getInputRevision(), attempt.getInputRevision())
                        && "succeeded".equals(attempt.getStatus()) && attempt.getStopConfirmedAt() != null
                        && attempt.getResultEvidence() != null && !attempt.getResultEvidence().isNull()
                        && validTraceOwner(task, binding, attempt))
                .reduce((first, second) -> second).orElse(null);
    }

    /** 已拆分的父任务：存在子任务且拆分事实已停止确认；完成由子任务汇总决定。 */
    boolean splitParent(Task task) {
        Long children = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getParentId, task.getId()));
        return children != null && children > 0 && TaskSplitFacts.confirmed(attemptMapper.selectByTaskId(task.getId()));
    }

    boolean hasExplanatoryDelivery(Task task, Long bindingId) {
        TaskExecutionAttempt delivery = currentDelivery(task, bindingId);
        return delivery != null && delivery.getResultEvidence().path("artifactRefs").isArray()
                && delivery.getResultEvidence().path("artifactRefs").isEmpty();
    }

    public record TraceCandidate(Long attemptId, Long inputRevision, JsonNode evidence) { }
    public record PrerequisiteResult(Long taskId, Long attemptId, Long inputRevision, JsonNode evidence,
                                     List<TraceCandidate> traceCandidates) { }
    public record InputEvaluation(boolean ready, String reason, List<PrerequisiteResult> prerequisites) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasDependency(Long taskId, Long prerequisiteId) {
        return dependencyMapper.selectByTaskId(taskId).stream()
                .anyMatch(edge -> Objects.equals(edge.getPrerequisiteTaskId(), prerequisiteId));
    }

    /** 调用方持有项目协调锁及当前尝试行锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean changeDependency(Task current, Long prerequisiteId, boolean add, Long actorUserId) {
        if (prerequisiteId == null || prerequisiteId <= 0 || Objects.equals(current.getId(), prerequisiteId))
            throw new IllegalArgumentException("依赖关系不能指向自身");
        Task prerequisite = requireTaskForUpdate(prerequisiteId, current.getProjectId());
        Long children = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getParentId, current.getId()));
        Long prerequisiteChildren = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getParentId, prerequisiteId));
        if (children == null || prerequisiteChildren == null) throw new IllegalStateException("子任务数量查询失败");
        if (children > 0 || prerequisiteChildren > 0) throw new ConflictOperationException("协作关系要求叶子任务");
        boolean exists = dependencyMapper.selectByTaskId(current.getId()).stream()
                .anyMatch(edge -> Objects.equals(edge.getPrerequisiteTaskId(), prerequisiteId));
        if (exists == add) return false;
        if (add) {
            Set<Long> visited = new LinkedHashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(prerequisite.getId());
            while (!queue.isEmpty()) {
                Long id = queue.removeFirst();
                if (Objects.equals(id, current.getId())) throw new ConflictOperationException("协作关系形成循环");
                if (visited.add(id)) {
                    for (TaskDependency edge : dependencyMapper.selectByTaskId(id)) queue.addLast(edge.getPrerequisiteTaskId());
                }
            }
            TaskDependency edge = new TaskDependency();
            edge.setTaskId(current.getId());
            edge.setPrerequisiteTaskId(prerequisiteId);
            if (dependencyMapper.insert(edge) != 1) throw new IllegalStateException("协作关系保存失败");
        } else {
            int removed = dependencyMapper.delete(new LambdaQueryWrapper<TaskDependency>()
                    .eq(TaskDependency::getTaskId, current.getId()).eq(TaskDependency::getPrerequisiteTaskId, prerequisiteId));
            if (removed != 1) throw new IllegalStateException("协作关系撤销失败");
        }
        invalidate(current.getProjectId(), Set.of(current.getId()), actorUserId, "协作关系变化");
        return true;
    }

    /** 复核服务校验来源交付与批次授权后，在同一协调事务中使其和下游失效。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reopenForRework(Long projectId, Long sourceTaskId, Long actorUserId) {
        Task source = requireTaskForUpdate(sourceTaskId, projectId);
        if ("todo".equalsIgnoreCase(source.getStatus()) || "in_progress".equalsIgnoreCase(source.getStatus())) return false;
        if (!"done".equalsIgnoreCase(source.getStatus()))
            throw new ConflictOperationException("前置交付已变化，请重新观察");
        invalidate(projectId, Set.of(sourceTaskId), actorUserId, "前置成果需要返工");
        return true;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTaskInputChanged(CollaborationTaskInputChangedEvent event) {
        if (!dependencyMapper.hasCollaborationParticipation(event.taskId())) return;
        if (event.inputChanged()) invalidate(event.projectId(), Set.of(event.taskId()), event.actorUserId(), "任务输入或前置成果变更");
        else if (event.stopRequested()) attemptMapper.requestStopForTask(event.taskId(), LocalDateTime.now(), "任务取消");
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onParentAssigned(CollaborationParentAssignedEvent event) {
        Task parent = requireTaskForUpdate(event.parentTaskId(), event.projectId());
        Long pending = attemptMapper.selectCount(new LambdaQueryWrapper<TaskExecutionAttempt>()
                .eq(TaskExecutionAttempt::getTaskId, parent.getId()).isNull(TaskExecutionAttempt::getStopConfirmedAt));
        if (pending == null) throw new IllegalStateException("协作执行数量查询失败");
        if (dependencyMapper.hasCollaborationParticipation(parent.getId()) || pending > 0) {
            throw new ConflictOperationException("参与协作的任务必须保持叶子节点");
        }
    }

    /** 调用方在项目协调事务内使用，保留冻结范围直到宿主确认停止。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requestProjectStop(Long projectId, Long userId) {
        requireProjectLock(projectId);
        accessGuard.requireOwner(projectId, userId);
        batchMapper.requestProjectStop(projectId, LocalDateTime.now(java.time.ZoneOffset.UTC));
        boolean batchPending = batchMapper.countAcceptedByProject(projectId) > 0;
        List<Long> bindingIds = bindingMapper.selectList(new LambdaQueryWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getProjectId, projectId)).stream().map(ProjectDeviceBinding::getId).toList();
        if (bindingIds.isEmpty()) return batchPending;
        List<TaskExecutionAttempt> pending = attemptMapper.selectList(new LambdaQueryWrapper<TaskExecutionAttempt>()
                .in(TaskExecutionAttempt::getBindingId, bindingIds).isNull(TaskExecutionAttempt::getStopConfirmedAt));
        for (Long taskId : pending.stream().map(TaskExecutionAttempt::getTaskId).distinct().toList()) {
            attemptMapper.requestStopForTask(taskId, LocalDateTime.now(), "项目删除");
        }
        return batchPending || !pending.isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearProjectFacts(Long projectId, List<Long> taskIds) {
        if (batchMapper.countAcceptedByProject(projectId) > 0)
            throw new ConflictOperationException("协作批次等待停止确认");
        List<Long> bindingIds = bindingMapper.selectList(new LambdaQueryWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getProjectId, projectId)).stream().map(ProjectDeviceBinding::getId).toList();
        if (!bindingIds.isEmpty()) {
            Long pending = attemptMapper.selectCount(new LambdaQueryWrapper<TaskExecutionAttempt>()
                    .in(TaskExecutionAttempt::getBindingId, bindingIds).isNull(TaskExecutionAttempt::getStopConfirmedAt));
            if (pending == null) throw new IllegalStateException("协作执行数量查询失败");
            if (pending > 0) throw new ConflictOperationException("协作执行等待停止确认");
            operationMapper.deleteByProjectId(projectId);
            attemptMapper.delete(new LambdaQueryWrapper<TaskExecutionAttempt>().in(TaskExecutionAttempt::getBindingId, bindingIds));
        }
        batchMapper.deleteTasksByProject(projectId);
        batchMapper.deleteByProject(projectId);
        if (bindingIds.isEmpty()) operationMapper.deleteByProjectId(projectId);
        if (!taskIds.isEmpty()) dependencyMapper.delete(new LambdaQueryWrapper<TaskDependency>()
                .in(TaskDependency::getTaskId, taskIds));
        bindingMapper.delete(new LambdaQueryWrapper<ProjectDeviceBinding>().eq(ProjectDeviceBinding::getProjectId, projectId));
    }

    private void invalidate(Long projectId, Set<Long> roots, Long actorUserId, String reason) {
        LinkedHashSet<Long> affected = new LinkedHashSet<>(roots);
        ArrayDeque<Long> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            for (TaskDependency edge : dependencyMapper.selectByPrerequisiteTaskId(queue.removeFirst())) {
                if (affected.add(edge.getTaskId())) queue.addLast(edge.getTaskId());
            }
        }
        LocalDateTime occurredAt = LocalDateTime.now();
        for (Long id : affected.stream().sorted().toList()) {
            Task task = requireTaskForUpdate(id, projectId);
            incrementRevision(task);
            attemptMapper.requestStopForTask(id, occurredAt, reason);
            if ("done".equalsIgnoreCase(task.getStatus())) taskStatusService.updateState(id, "todo", 0, actorUserId, occurredAt);
        }
    }

    private void incrementRevision(Task task) {
        if (task.getInputRevision() == null) throw new IllegalStateException("任务输入版本缺失");
        taskMapper.update(null, new UpdateWrapper<Task>().eq("id", task.getId()).set("input_revision", task.getInputRevision() + 1));
    }

    private Task requireTaskForUpdate(Long id, Long projectId) {
        Task task = taskMapper.selectByIdForUpdate(id);
        if (task == null || !Objects.equals(projectId, task.getProjectId())) throw new ResourceNotFoundException("项目任务不存在: " + id);
        return task;
    }

    private void requireProjectLock(Long projectId) {
        if (projectMapper.lockProject(projectId) == null) throw new ResourceNotFoundException("项目不存在: " + projectId);
    }


}
