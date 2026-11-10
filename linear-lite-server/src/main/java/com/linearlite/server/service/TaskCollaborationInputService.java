package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.PublicContextRef;
import com.linearlite.server.dto.TaskCollaborationInputs;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskDependency;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDeviceBindingMapper;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TaskCollaborationInputService {
    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final TaskDependencyMapper dependencyMapper;
    private final TaskExecutionAttemptMapper attemptMapper;
    private final TaskExecutionOperationMapper operationMapper;
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
                                         TaskExecutionOperationMapper operationMapper) {
        this.projectMapper = projectMapper;
        this.taskMapper = taskMapper;
        this.dependencyMapper = dependencyMapper;
        this.attemptMapper = attemptMapper;
        this.operationMapper = operationMapper;
        this.documentMapper = documentMapper;
        this.bindingMapper = bindingMapper;
        this.permissionGuard = permissionGuard;
        this.accessGuard = accessGuard;
        this.taskStatusService = taskStatusService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Task replaceInputs(String taskKey, TaskCollaborationInputs input, Long userId) {
        if (input == null || input.inputRevision() == null || input.agentExecutionEnabled() == null
                || input.writeScopes() == null || input.publicContextRefs() == null
                || input.prerequisiteTaskIds() == null) {
            throw new IllegalArgumentException("协作输入参数不完整");
        }
        Task observed = permissionGuard.requireTaskAccessByKey(taskKey, userId);
        requireProjectLock(observed.getProjectId());
        Task current = requireTaskForUpdate(observed.getId(), observed.getProjectId());
        permissionGuard.requireProjectMember(current.getProjectId(), userId);
        requireRevision(current, input.inputRevision());
        requireLeaf(current);

        List<String> scopes = CollaborationPathScopeService.validate(input.writeScopes());
        List<PublicContextRef> refs = validateReferences(current.getProjectId(), input.publicContextRefs());
        List<Long> prerequisites = validateDependencies(current, input.prerequisiteTaskIds());
        List<Long> previousDependencies = dependencyMapper.selectByTaskId(current.getId()).stream()
                .map(TaskDependency::getPrerequisiteTaskId).sorted().toList();
        JsonNode nextRefs = objectMapper.valueToTree(refs);
        boolean changed = !Objects.equals(current.getAgentExecutionEnabled(), input.agentExecutionEnabled())
                || !Objects.equals(current.getWriteScopes(), scopes)
                || !Objects.equals(current.getPublicContextRefs(), nextRefs)
                || !previousDependencies.equals(prerequisites);
        if (!changed) return current;

        boolean wasParticipating = dependencyMapper.hasCollaborationParticipation(current.getId());

        Task replacement = new Task();
        replacement.setId(current.getId());
        replacement.setAgentExecutionEnabled(input.agentExecutionEnabled());
        replacement.setWriteScopes(scopes);
        replacement.setPublicContextRefs(nextRefs);
        taskMapper.updateById(replacement);
        if (!previousDependencies.equals(prerequisites)) {
            dependencyMapper.delete(new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getTaskId, current.getId()));
            for (Long prerequisite : prerequisites) {
                TaskDependency edge = new TaskDependency();
                edge.setTaskId(current.getId());
                edge.setPrerequisiteTaskId(prerequisite);
                dependencyMapper.insert(edge);
            }
        }
        boolean willParticipate = input.agentExecutionEnabled() || !prerequisites.isEmpty() || !refs.isEmpty();
        if (wasParticipating || willParticipate) {
            invalidate(current.getProjectId(), Set.of(current.getId()), userId, "协作输入变更");
        } else {
            incrementRevision(current);
        }
        return taskMapper.selectById(current.getId());
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
        return new TaskCollaborationInputs(current.getInputRevision(), current.getAgentExecutionEnabled(),
                current.getWriteScopes(), decodeReferences(current.getPublicContextRefs()), prerequisites);
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

    /** 项目锁由调用方持有；不以异常表达可预期的就绪阻塞。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InputEvaluation evaluateInputs(Task current, Long expectedRevision, Long bindingId) {
        if (expectedRevision == null || !Objects.equals(current.getInputRevision(), expectedRevision))
            return new InputEvaluation(false, "任务输入版本已变化", List.of());
        Long children = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getParentId, current.getId()));
        if (children == null) throw new IllegalStateException("子任务数量查询失败");
        if (children > 0) return new InputEvaluation(false, "协作执行要求叶子任务", List.of());
        for (PublicContextRef ref : decodeReferences(current.getPublicContextRefs())) {
            ProjectDocument document = documentMapper.selectById(ref.documentId());
            if (document == null || document.getArchivedAt() != null
                    || !Objects.equals(current.getProjectId(), document.getProjectId())
                    || !Objects.equals(ref.version(), document.getVersion()))
                return new InputEvaluation(false, "公共约定不可用或版本已变化", List.of());
        }
        List<PrerequisiteResult> results = new ArrayList<>();
        for (TaskDependency edge : dependencyMapper.selectByTaskId(current.getId())) {
            Task prerequisite = taskMapper.selectById(edge.getPrerequisiteTaskId());
            if (prerequisite == null || !Objects.equals(prerequisite.getProjectId(), current.getProjectId())
                    || !"done".equalsIgnoreCase(prerequisite.getStatus())) {
                return new InputEvaluation(false, "前置任务缺少成功成果", results);
            }
            Long prerequisiteChildren = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getParentId, prerequisite.getId()));
            if (prerequisiteChildren == null) throw new IllegalStateException("子任务数量查询失败");
            if (prerequisiteChildren > 0) return new InputEvaluation(false, "前置任务不是叶子任务", results);
            TaskExecutionAttempt delivered = attemptMapper.selectByTaskId(prerequisite.getId()).stream()
                    .filter(attempt -> Objects.equals(bindingId, attempt.getBindingId())
                            && Objects.equals(prerequisite.getInputRevision(), attempt.getInputRevision())
                            && "succeeded".equals(attempt.getStatus()) && attempt.getStopConfirmedAt() != null
                            && attempt.getResultEvidence() != null && !attempt.getResultEvidence().isNull())
                    .reduce((first, second) -> second).orElse(null);
            if (delivered == null) return new InputEvaluation(false, "前置任务缺少同绑定有效成果", results);
            results.add(new PrerequisiteResult(prerequisite.getId(), delivered.getId(), delivered.getInputRevision(),
                    delivered.getResultEvidence()));
        }
        return new InputEvaluation(true, null, results);
    }

    public record PrerequisiteResult(Long taskId, Long attemptId, Long inputRevision, JsonNode evidence) { }
    public record InputEvaluation(boolean ready, String reason, List<PrerequisiteResult> prerequisites) { }

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

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDocumentChanged(CollaborationDocumentChangedEvent event) {
        Map<Long, Task> roots = new LinkedHashMap<>();
        Set<Long> changedRefs = new HashSet<>();
        for (CollaborationDocumentChangedEvent.Change change : event.changes()) {
            if (change.available() && change.version() == null) throw new IllegalArgumentException("文档版本不能为空");
            for (Task observed : taskMapper.selectReferencingDocumentForUpdate(event.projectId(), change.documentId())) {
                Task task = roots.computeIfAbsent(observed.getId(), ignored -> observed);
                if (change.available()) {
                    ArrayNode updated = ((ArrayNode) task.getPublicContextRefs()).deepCopy();
                    for (JsonNode node : updated) {
                        if (node.path("documentId").asLong() == change.documentId()) {
                            ((ObjectNode) node).put("version", change.version());
                        }
                    }
                    task.setPublicContextRefs(updated);
                    changedRefs.add(task.getId());
                }
            }
        }
        for (Long id : changedRefs) {
            Task patch = new Task();
            patch.setId(id);
            patch.setPublicContextRefs(roots.get(id).getPublicContextRefs());
            taskMapper.updateById(patch);
        }
        if (!roots.isEmpty()) invalidate(event.projectId(), roots.keySet(), event.actorUserId(), "公共约定变更");
    }

    /** 调用方在项目协调事务内使用，保留冻结范围直到宿主确认停止。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requestProjectStop(Long projectId, Long userId) {
        requireProjectLock(projectId);
        accessGuard.requireOwner(projectId, userId);
        List<Long> bindingIds = bindingMapper.selectList(new LambdaQueryWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getProjectId, projectId)).stream().map(ProjectDeviceBinding::getId).toList();
        if (bindingIds.isEmpty()) return false;
        List<TaskExecutionAttempt> pending = attemptMapper.selectList(new LambdaQueryWrapper<TaskExecutionAttempt>()
                .in(TaskExecutionAttempt::getBindingId, bindingIds).isNull(TaskExecutionAttempt::getStopConfirmedAt));
        for (Long taskId : pending.stream().map(TaskExecutionAttempt::getTaskId).distinct().toList()) {
            attemptMapper.requestStopForTask(taskId, LocalDateTime.now(), "项目删除");
        }
        return !pending.isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearProjectFacts(Long projectId, List<Long> taskIds) {
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

    private void requireLeaf(Task task) {
        Long children = taskMapper.selectCount(new LambdaQueryWrapper<Task>().eq(Task::getParentId, task.getId()));
        if (children == null) throw new IllegalStateException("子任务数量查询失败");
        if (children > 0) throw new IllegalArgumentException("协作执行和依赖要求叶子任务");
    }

    private void requireRevision(Task task, Long revision) {
        if (revision == null || !Objects.equals(task.getInputRevision(), revision)) throw new ConflictOperationException("任务输入版本已变化");
    }

    private List<Long> validateDependencies(Task current, List<Long> input) {
        if (input.stream().anyMatch(Objects::isNull) || new HashSet<>(input).size() != input.size())
            throw new IllegalArgumentException("前置任务存在空值或重复");
        for (Long id : input) {
            if (id.equals(current.getId())) throw new IllegalArgumentException("任务不能依赖自身");
            Task prerequisite = taskMapper.selectById(id);
            if (prerequisite == null || !Objects.equals(current.getProjectId(), prerequisite.getProjectId()))
                throw new IllegalArgumentException("前置任务必须属于同一项目");
            requireLeaf(prerequisite);
            Set<Long> seen = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(id);
            while (!queue.isEmpty()) {
                Long next = queue.removeFirst();
                if (next.equals(current.getId())) throw new IllegalArgumentException("任务依赖不能形成环");
                if (seen.add(next)) dependencyMapper.selectByTaskId(next).stream()
                        .map(TaskDependency::getPrerequisiteTaskId).forEach(queue::addLast);
            }
        }
        return input.stream().sorted().toList();
    }

    private List<PublicContextRef> validateReferences(Long projectId, List<PublicContextRef> input) {
        Set<Long> seen = new HashSet<>();
        for (PublicContextRef ref : input) {
            if (ref == null || ref.documentId() == null || ref.version() == null || !seen.add(ref.documentId()))
                throw new IllegalArgumentException("公共约定引用存在空值或重复");
            ProjectDocument document = documentMapper.selectById(ref.documentId());
            if (document == null || document.getArchivedAt() != null || !Objects.equals(projectId, document.getProjectId())
                    || !Objects.equals(ref.version(), document.getVersion()))
                throw new ConflictOperationException("公共约定不可用或版本已变化");
        }
        return input.stream().sorted(Comparator.comparing(PublicContextRef::documentId)).toList();
    }

    private List<PublicContextRef> decodeReferences(JsonNode refs) {
        if (refs == null || !refs.isArray()) throw new IllegalStateException("公共约定引用格式无效");
        List<PublicContextRef> result = new ArrayList<>();
        for (JsonNode ref : refs) {
            if (!ref.isObject() || !ref.path("documentId").isIntegralNumber() || !ref.path("version").isIntegralNumber())
                throw new IllegalStateException("公共约定引用格式无效");
            result.add(new PublicContextRef(ref.get("documentId").longValue(), ref.get("version").longValue()));
        }
        return result;
    }

}
