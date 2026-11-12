package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.TaskSplitRequest;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.CollaborationBatchMapper;
import com.linearlite.server.mapper.TaskDependencyMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持有任务的 Agent 把任务拆成子任务。子任务只携带需求，不声明依赖；
 * 依赖仍由后续 Agent 按发现的事实维护。子任务属于原授权任务的后代，并入本批授权集合。
 */
@Service
public class TaskCollaborationSplitService {
    static final int MAX_SUBTASKS = 8;
    static final int MAX_BATCH_TASKS = 200;
    static final int MAX_ANCESTORS = 2;
    private final TaskExecutionAccessService access;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionAttemptMapper attempts;
    private final TaskMapper tasks;
    private final TaskDependencyMapper dependencies;
    private final CollaborationBatchMapper batches;
    private final TaskCommandService taskCommands;
    private final MarkdownToBlockNoteConverter descriptions;
    private final ObjectMapper json;

    public TaskCollaborationSplitService(TaskExecutionAccessService access, TaskExecutionOperationService operations,
            TaskExecutionAttemptMapper attempts, TaskMapper tasks, TaskDependencyMapper dependencies,
            CollaborationBatchMapper batches, TaskCommandService taskCommands,
            MarkdownToBlockNoteConverter descriptions, ObjectMapper json) {
        this.access = access;
        this.operations = operations;
        this.attempts = attempts;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.batches = batches;
        this.taskCommands = taskCommands;
        this.descriptions = descriptions;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode split(Long userId, CollaborationBatchRequest batch, Long taskId,
                          CollaborationAttemptCommand command, TaskSplitRequest split) {
        validate(split);
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = json.createObjectNode().put("taskId", taskId)
                .put("batchId", owned.context().batchId()).put("bindingId", owned.context().bindingId())
                .put("attemptId", command.attemptId()).put("inputRevision", command.inputRevision())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId());
        request.set("split", json.valueToTree(split));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken(), command.claimToken());
        CollaborationCredentialPolicy.requireSafe(command.operationId(), batch.batchToken(), command.claimToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), TaskSplitFacts.KIND, request);
        if (replay != null) return replay;

        var check = access.checkWritable(owned, command);
        ObjectNode response = json.createObjectNode().put("accepted", check.accepted());
        String refusal = check.accepted() ? refusal(owned, split) : check.reason();
        if (refusal != null) {
            response.put("accepted", false).put("reason", refusal).put("refused", check.accepted());
        } else {
            create(userId, owned, command, split, response);
        }
        operations.save(owned.context().projectId(), userId, command.operationId(), TaskSplitFacts.KIND,
                command.attemptId(), request, response);
        return response;
    }

    private String refusal(TaskExecutionAccessService.OwnedAttempt owned, TaskSplitRequest split) {
        Task task = owned.task();
        Long children = tasks.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task>()
                .eq(Task::getParentId, task.getId()));
        if (children == null) throw new IllegalStateException("子任务数量查询失败");
        if (children > 0) return "任务已有子任务，不能重复拆分";
        // 下游任务以本任务的成果为前置，拆分后本任务没有自己的交付，因此不允许。
        if (!dependencies.selectByPrerequisiteTaskId(task.getId()).isEmpty()) return "任务已被其他任务依赖，不能拆分";
        int ancestors = 0;
        Long parentId = task.getParentId();
        while (parentId != null && ancestors <= MAX_ANCESTORS) {
            Task parent = tasks.selectById(parentId);
            if (parent == null) break;
            ancestors++;
            parentId = parent.getParentId();
        }
        if (ancestors >= MAX_ANCESTORS) return "拆分层级已达上限";
        if (batches.countTasks(owned.context().batchId()) + split.subtasks().size() > MAX_BATCH_TASKS)
            return "批次任务数量已达上限";
        return null;
    }

    private void create(Long userId, TaskExecutionAccessService.OwnedAttempt owned, CollaborationAttemptCommand command,
                        TaskSplitRequest split, ObjectNode response) {
        Task parent = owned.task();
        ArrayNode created = json.createArrayNode();
        for (TaskSplitRequest.Subtask subtask : split.subtasks()) {
            String body = (subtask.description() == null ? "" : subtask.description().strip())
                    + "\n\n拆分自 " + parent.getTaskKey() + " " + parent.getTitle();
            // 先建为独立任务再挂到父任务：用户侧挂子任务会被协作叶子规则拒绝，这里是该规则唯一的例外入口。
            Task child = taskCommands.create(parent.getProjectId(), userId, null, subtask.title().strip(),
                    descriptions.convertTaskDescription(body), "todo", parent.getPriority(), parent.getAssigneeKey(),
                    null, null, 0, List.of()).task();
            if (tasks.update(null, new UpdateWrapper<Task>().eq("id", child.getId()).set("parent_id", parent.getId())) != 1)
                throw new IllegalStateException("子任务挂接失败");
            if (batches.insertTask(owned.context().batchId(), child.getId()) != 1)
                throw new IllegalStateException("子任务并入批次失败");
            created.addObject().put("taskId", child.getId()).put("taskKey", child.getTaskKey()).put("title", child.getTitle());
        }
        int added = split.subtasks().size();
        if (batches.addAttemptBudget(owned.context().batchId(), added) != 1) throw new IllegalStateException("批次预算更新失败");
        access.requestStop(owned.attempt(), "任务已拆分");
        response.put("outcome", "SPLIT").put("stopRequested", true).put("addedAttempts", added);
        response.set("subtasks", created);
        appendFact(owned, command, split, response);
    }

    private void appendFact(TaskExecutionAccessService.OwnedAttempt owned, CollaborationAttemptCommand command,
                            TaskSplitRequest split, JsonNode response) {
        JsonNode previous = owned.attempt().getCheckpoints();
        if (previous == null || !previous.isArray()) throw new IllegalStateException("检查点数据无效");
        ObjectNode fact = json.createObjectNode().put("operationId", command.operationId())
                .put("agentInstanceId", command.agentInstanceId()).put("occurredAt", LocalDateTime.now().toString())
                .put("kind", TaskSplitFacts.KIND).put("reason", split.reason());
        fact.set("subtasks", response.path("subtasks").deepCopy());
        try {
            if (attempts.appendCheckpoint(command.attemptId(), previous.size(), json.writeValueAsString(fact)) != 1)
                throw new IllegalStateException("拆分事实保存失败");
        } catch (JsonProcessingException e) { throw new IllegalStateException("拆分事实序列化失败", e); }
    }

    private void validate(TaskSplitRequest split) {
        if (split == null || split.reason() == null || split.reason().isBlank() || split.reason().length() > 2048
                || split.subtasks() == null || split.subtasks().isEmpty() || split.subtasks().size() > MAX_SUBTASKS)
            throw new IllegalArgumentException("拆分请求无效");
        for (TaskSplitRequest.Subtask subtask : split.subtasks()) {
            if (subtask == null || subtask.title() == null || subtask.title().isBlank() || subtask.title().length() > 256
                    || (subtask.description() != null && subtask.description().length() > 8000))
                throw new IllegalArgumentException("子任务标题或描述无效");
        }
        if (split.subtasks().stream().map(s -> s.title().strip()).distinct().count() != split.subtasks().size())
            throw new IllegalArgumentException("子任务标题不能重复");
    }
}
