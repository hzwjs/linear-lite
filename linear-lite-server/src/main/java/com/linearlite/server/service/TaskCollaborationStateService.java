package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskCollaborationState;
import com.linearlite.server.dto.TaskCollaborationState.*;
import com.linearlite.server.dto.TaskExecutionAttemptResponse;
import com.linearlite.server.entity.ProjectDeviceBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ResourceNotFoundException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TaskCollaborationStateService {
    private final TaskPermissionGuard permissions;
    private final ProjectMapper projects;
    private final TaskMapper tasks;
    private final ProjectDeviceBindingMapper bindings;
    private final TaskExecutionAttemptMapper attempts;
    private final TaskCollaborationInputService inputs;
    private final CollaborationContextService contexts;
    private final TaskPrerequisiteReviewService reviews;
    private final TaskCollaborationDependencyService dependencies;
    private final CollaborationGoalRequests goals;
    private final Clock clock;

    @Autowired
    public TaskCollaborationStateService(TaskPermissionGuard permissions, ProjectMapper projects, TaskMapper tasks,
            ProjectDeviceBindingMapper bindings, TaskExecutionAttemptMapper attempts, TaskCollaborationInputService inputs,
            CollaborationContextService contexts, TaskPrerequisiteReviewService reviews,
            TaskCollaborationDependencyService dependencies, CollaborationGoalRequests goals) {
        this(permissions, projects, tasks, bindings, attempts, inputs, contexts, reviews, dependencies, goals, Clock.systemUTC());
    }

    TaskCollaborationStateService(TaskPermissionGuard permissions, ProjectMapper projects, TaskMapper tasks,
            ProjectDeviceBindingMapper bindings, TaskExecutionAttemptMapper attempts,
            TaskCollaborationInputService inputs, CollaborationContextService contexts, TaskPrerequisiteReviewService reviews,
            TaskCollaborationDependencyService dependencies, CollaborationGoalRequests goals, Clock clock) {
        this.permissions = permissions; this.projects = projects; this.tasks = tasks;
        this.bindings = bindings; this.attempts = attempts; this.inputs = inputs; this.contexts = contexts; this.reviews = reviews; this.clock = clock;
        this.dependencies = dependencies; this.goals = goals;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TaskCollaborationState get(Long userId, Request request) {
        if (request == null || request.taskKey() == null || request.taskKey().isBlank())
            throw new IllegalArgumentException("任务不能为空");
        Task observed = permissions.requireTaskAccessByKey(request.taskKey(), userId);
        if (projects.lockProject(observed.getProjectId()) == null) throw new ResourceNotFoundException("项目不存在");
        Task current = tasks.selectByIdForUpdate(observed.getId());
        if (current == null || !Objects.equals(current.getProjectId(), observed.getProjectId()))
            throw new ResourceNotFoundException("任务不存在或项目发生变化");
        permissions.requireProjectMember(current.getProjectId(), userId);
        List<TaskExecutionAttempt> pending = attempts.selectUnconfirmedByProject(current.getProjectId());
        List<TaskExecutionAttempt> history = attempts.selectByTaskId(current.getId());
        var dependencyFacts = dependencies.listFacts(current.getProjectId(), current.getId());
        boolean active = pending.stream().anyMatch(a -> Objects.equals(a.getTaskId(), current.getId()));
        List<BindingEvaluation> evaluations = new ArrayList<>();
        for (ProjectDeviceBinding binding : bindings.selectList(new LambdaQueryWrapper<ProjectDeviceBinding>()
                .eq(ProjectDeviceBinding::getUserId, userId).eq(ProjectDeviceBinding::getProjectId, current.getProjectId())
                .isNull(ProjectDeviceBinding::getDeletedAt).orderByAsc(ProjectDeviceBinding::getId))) {
            var evaluation = inputs.evaluateInputs(current, current.getInputRevision(), binding.getId());
            evaluations.add(new BindingEvaluation(binding.getId(), binding.getDeviceId(), evaluation.ready(),
                    evaluation.reason(), evaluation.prerequisites(), TaskPrerequisiteReviewService.projectStatuses(
                            current, binding.getId(), evaluation.prerequisites(), history, List.of())));
        }
        // 历史记录允许显示设备标识，不扩展其他成员的本机路径可见范围。
        var historyBindings = new LinkedHashMap<Long, BindingIdentity>();
        for (Long bindingId : history.stream().map(TaskExecutionAttempt::getBindingId).distinct().toList()) {
            ProjectDeviceBinding binding = bindings.selectById(bindingId);
            if (binding != null && Objects.equals(binding.getProjectId(), current.getProjectId()))
                historyBindings.put(binding.getId(), new BindingIdentity(binding.getId(), binding.getDeviceId()));
        }
        return new TaskCollaborationState(clock.instant(), current.getInputRevision(), current.getStatus(), current.getProgressPercent(),
                current.getCompletedAt(), current.getUpdatedAt(), active,
                history.stream().map(TaskExecutionAttemptResponse::from).toList(),
                List.copyOf(historyBindings.values()), List.copyOf(evaluations), history.stream()
                        .map(attempt -> new AttemptContext(attempt.getId(), contexts.project(attempt, current.getProjectId(), Map.of())))
                        .toList(), history.stream().map(a -> new AttemptPrerequisiteReviews(a.getId(), reviews.project(a))).toList(),
                dependencyFacts, goals.project(current, userId, null, history, dependencyFacts));
    }
}
