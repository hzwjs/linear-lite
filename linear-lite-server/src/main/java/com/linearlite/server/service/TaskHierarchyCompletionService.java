package com.linearlite.server.service;

import com.linearlite.server.dto.DirectChildCompletion;
import com.linearlite.server.dto.TaskStateChange;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class TaskHierarchyCompletionService {
    private static final Set<String> AUTO_COMPLETABLE_STATUSES = Set.of(
            "backlog", "todo", "in_progress", "in_review");

    private final TaskMapper taskMapper;
    private final TaskStatusService taskStatusService;

    public TaskHierarchyCompletionService(TaskMapper taskMapper, TaskStatusService taskStatusService) {
        this.taskMapper = taskMapper;
        this.taskStatusService = taskStatusService;
    }

    public List<TaskStateChange> completeEligibleAncestors(
            Long childTaskId, Long actorUserId, LocalDateTime occurredAt) {
        Task child = taskMapper.selectById(childTaskId);
        if (child == null) {
            throw new IllegalArgumentException("任务不存在: " + childTaskId);
        }
        return completeEligibleParentChain(child.getParentId(), actorUserId, occurredAt);
    }

    public List<TaskStateChange> completeEligibleParentChain(
            Long parentTaskId, Long actorUserId, LocalDateTime occurredAt) {
        List<TaskStateChange> changes = new ArrayList<>();
        Long currentParentId = parentTaskId;
        while (currentParentId != null) {
            ParentEvaluation evaluation = evaluateParent(currentParentId, actorUserId, occurredAt);
            if (!evaluation.advance()) {
                break;
            }
            if (evaluation.change() != null) {
                changes.add(evaluation.change());
            }
            currentParentId = evaluation.nextParentId();
        }
        return changes;
    }

    /** Import uses this one-level form after sorting parents deepest-first, avoiding repeated ancestor checks. */
    public List<TaskStateChange> completeEligibleParent(
            Long parentTaskId, Long actorUserId, LocalDateTime occurredAt) {
        ParentEvaluation evaluation = evaluateParent(parentTaskId, actorUserId, occurredAt);
        return evaluation.change() == null ? List.of() : List.of(evaluation.change());
    }

    private ParentEvaluation evaluateParent(Long parentTaskId, Long actorUserId, LocalDateTime occurredAt) {
        // Lock each parent before counting its children so concurrent last-child updates serialize here.
        Task parent = taskMapper.selectByIdForUpdate(parentTaskId);
        if (parent == null) {
            return new ParentEvaluation(false, null, null);
        }
        DirectChildCompletion completion = taskMapper.selectDirectChildCompletion(parentTaskId);
        long total = completion.getTotalCount();
        long terminal = completion.getTerminalCount();
        if (total == 0 || terminal != total) {
            return new ParentEvaluation(false, null, null);
        }
        if ("done".equalsIgnoreCase(parent.getStatus())) {
            return new ParentEvaluation(true, parent.getParentId(), null);
        }
        if (!isAutoCompletable(parent.getStatus())) {
            return new ParentEvaluation(false, null, null);
        }
        Task completed = taskStatusService.updateState(parent.getId(), "done", 100, actorUserId, occurredAt);
        TaskStateChange change = new TaskStateChange(
                completed.getId(), completed.getTaskKey(), completed.getStatus(), completed.getProgressPercent(),
                completed.getCompletedAt(), completed.getUpdatedAt());
        return new ParentEvaluation(true, completed.getParentId(), change);
    }

    private record ParentEvaluation(boolean advance, Long nextParentId, TaskStateChange change) {
    }

    private static boolean isAutoCompletable(String status) {
        return status != null && AUTO_COMPLETABLE_STATUSES.contains(status.toLowerCase());
    }
}
