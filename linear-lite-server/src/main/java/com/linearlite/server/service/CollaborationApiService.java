package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskDeliveryEvidence;
import com.linearlite.server.dto.TaskExecutionCheckpoint;
import com.linearlite.server.dto.CollaborationContextRef;
import com.linearlite.server.dto.DependencyChange;
import com.linearlite.server.dto.PrerequisiteSourceObservation;
import com.linearlite.server.dto.ArtifactObservation;
import com.linearlite.server.dto.PrerequisiteReviewRequest;
import com.linearlite.server.exception.UnauthorizedException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** REST 与 MCP 共用请求契约和领域服务；事务边界留在领域服务。 */
@Service
public class CollaborationApiService {
    private final ObjectMapper json;
    private final ProjectDeviceBindingService bindings;
    private final TaskCollaborationInputService inputs;
    private final TaskCollaborationClaimService claims;
    private final TaskExecutionLifecycleService lifecycle;
    private final TaskCollaborationCompletionService completion;
    private final UnacknowledgedClaimRecoveryService unacknowledgedClaims;
    private final CollaborationContextService contexts;
    private final TaskCollaborationDependencyService dependencies;
    private final TaskPrerequisiteReviewService reviews;
    private final TaskCollaborationSplitService splits;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    public CollaborationApiService(ObjectMapper json, ProjectDeviceBindingService bindings,
            TaskCollaborationInputService inputs,
            TaskCollaborationClaimService claims, TaskExecutionLifecycleService lifecycle,
            TaskCollaborationCompletionService completion, UnacknowledgedClaimRecoveryService unacknowledgedClaims,
            CollaborationContextService contexts, TaskCollaborationDependencyService dependencies, TaskPrerequisiteReviewService reviews,
            TaskCollaborationSplitService splits) {
        this.json = json;
        this.bindings = bindings;
        this.inputs = inputs;
        this.claims = claims;
        this.lifecycle = lifecycle;
        this.completion = completion;
        this.unacknowledgedClaims = unacknowledgedClaims;
        this.contexts = contexts;
        this.dependencies = dependencies;
        this.reviews = reviews;
        this.splits = splits;
        defineOperations();
    }

    public List<Definition> definitions() { return List.copyOf(definitions.values()); }
    public Definition definition(String name) { return definitions.get(name); }

    public JsonNode invoke(String name, JsonNode request, Long userId) {
        if (userId == null || userId <= 0) throw new UnauthorizedException("当前用户未登录");
        Definition definition = definitions.get(name);
        if (definition == null) throw new ParameterException("协作操作不存在");
        validate(request, definition.inputSchema());
        Object result = switch (name) {
            case "save_project_device_binding" -> bindings.save(userId, number(request, "projectId"),
                    text(request, "deviceId"), text(request, "projectDirectory"));
            case "get_project_device_binding" -> bindings.get(userId, number(request, "projectId"), text(request, "deviceId"));
            case "list_project_device_bindings" -> Map.of("bindings", bindings.list(userId, number(request, "projectId")));
            case "delete_project_device_binding" -> deleteBinding(userId, request);
            case "get_task_collaboration_inputs" -> inputs.getInputs(text(request, "taskKey"), userId);
            case "observe_collaboration_environment" -> claims.observe(userId, batch(request), sourceObservations(request));
            case "claim_collaboration_task" -> claims.claim(userId, batch(request), number(request, "taskId"),
                    number(request, "inputRevision"), text(request, "hostInstanceId"), text(request, "agentInstanceId"),
                    text(request, "operationId"), sourceObservations(request));
            case "list_collaboration_documents" -> Map.of("documents", contexts.listDocuments(userId, batch(request), number(request, "taskId"), command(request)));
            case "read_collaboration_document" -> contexts.readDocument(userId, batch(request), number(request, "taskId"), command(request), number(request, "documentId"));
            case "adopt_collaboration_context" -> contexts.adopt(userId, batch(request), number(request, "taskId"), command(request), contextRefs(request.get("contextRefs")));
            case "change_collaboration_dependency" -> dependencies.change(userId, batch(request), number(request, "taskId"), command(request), convert(request.get("change"), DependencyChange.class));
            case "split_collaboration_task" -> splits.split(userId, batch(request), number(request, "taskId"), command(request), convert(request.get("split"), com.linearlite.server.dto.TaskSplitRequest.class));
            case "review_collaboration_prerequisite" -> reviews.review(userId, batch(request), number(request, "taskId"), command(request), convert(request.get("review"), PrerequisiteReviewRequest.class));
            case "renew_collaboration_attempt" -> lifecycle.renew(userId, batch(request), number(request, "taskId"), command(request));
            case "append_collaboration_checkpoint" -> checkpoint(userId, request);
            case "complete_collaboration_task" -> completion.complete(userId, batch(request), number(request, "taskId"),
                    command(request), convert(request.get("evidence"), TaskDeliveryEvidence.class),
                    convert(request.get("confirmation"), CollaborationStopConfirmation.class));
            case "get_collaboration_recovery" -> lifecycle.readRecovery(userId, text(request, "batchToken"), number(request, "projectId"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"), command(request));
            case "recover_unacknowledged_claim" -> unacknowledgedClaims.recover(userId, text(request, "batchToken"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"),
                    number(request, "inputRevision"), text(request, "hostInstanceId"), text(request, "agentInstanceId"), text(request, "claimOperationId"),
                    text(request, "operationId"), convert(request.get("confirmation"), CollaborationStopConfirmation.class));
            case "request_collaboration_stop" -> lifecycle.requestStop(userId, text(request, "batchToken"), number(request, "projectId"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"),
                    command(request), text(request, "reason"));
            case "confirm_collaboration_stop" -> lifecycle.confirmStop(userId, text(request, "batchToken"), number(request, "projectId"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"), command(request),
                    convert(request.get("confirmation"), CollaborationStopConfirmation.class));
            default -> throw new ParameterException("协作操作不存在");
        };
        return json.valueToTree(result);
    }

    private JsonNode deleteBinding(Long userId, JsonNode request) {
        bindings.delete(userId, number(request, "projectId"), text(request, "deviceId"));
        return json.createObjectNode().put("deleted", true);
    }

    private Object checkpoint(Long userId, JsonNode request) {
        var command = command(request);
        JsonNode item = request.get("checkpoint");
        TaskExecutionCheckpoint fact = new TaskExecutionCheckpoint(command.operationId(), command.agentInstanceId(),
                localDateTime(item, "occurredAt"), optionalText(item, "finding"), stringList(item.get("artifactRefs")),
                optionalText(item, "blockedReason"), contextRefs(item.get("contextRefs")),
                artifactObservations(item.get("artifactObservations")), optionalText(item, "previousContentVersion"),
                optionalText(item, "previousPath"));
        return lifecycle.checkpoint(userId, batch(request), number(request, "taskId"), command, fact);
    }

    private List<CollaborationContextRef> contextRefs(JsonNode array) {
        List<CollaborationContextRef> result = new ArrayList<>();
        array.forEach(value -> result.add(convert(value, CollaborationContextRef.class)));
        return result;
    }

    private CollaborationBatchRequest batch(JsonNode request) {
        return convert(request.get("batch"), CollaborationBatchRequest.class);
    }

    private CollaborationAttemptCommand command(JsonNode request) {
        return convert(request.get("command"), CollaborationAttemptCommand.class);
    }

    private List<PrerequisiteSourceObservation> sourceObservations(JsonNode request) {
        List<PrerequisiteSourceObservation> result = new ArrayList<>();
        for (JsonNode value : request.get("sourceObservations")) result.add(convert(value, PrerequisiteSourceObservation.class));
        return result;
    }

    private List<ArtifactObservation> artifactObservations(JsonNode array) {
        List<ArtifactObservation> result = new ArrayList<>();
        for (JsonNode value : array) result.add(convert(value, ArtifactObservation.class));
        return result;
    }

    private List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.textValue()));
        return result;
    }

    private String text(JsonNode value, String field) { return value.get(field).textValue(); }
    private String optionalText(JsonNode value, String field) { return value.has(field) ? value.get(field).textValue() : null; }
    private Long number(JsonNode value, String field) { return value.get(field).longValue(); }

    private LocalDateTime localDateTime(JsonNode value, String field) {
        try { return LocalDateTime.parse(text(value, field)); }
        catch (DateTimeParseException e) { throw new ParameterException("协作时间参数无效"); }
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try { return json.treeToValue(value, type); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw new ParameterException("协作参数格式无效"); }
    }

    private void defineOperations() {
        JsonNode id = integer();
        JsonNode device = string(128, true);
        JsonNode directory = string(2048, true);
        JsonNode key = string(32, true);
        JsonNode token = string(0, true);
        JsonNode instance = string(128, true);
        JsonNode operation = string(128, true);
        JsonNode strings = array(string(0, false));
        JsonNode batch = object(Map.of("batchToken", token, "bindingId", id, "deviceId", device,
                "projectDirectory", directory), "batchToken", "bindingId", "deviceId", "projectDirectory");
        JsonNode command = object(Map.of("attemptId", id, "claimToken", token, "inputRevision", id,
                "hostInstanceId", instance, "agentInstanceId", instance, "operationId", operation),
                "attemptId", "claimToken", "inputRevision", "hostInstanceId", "agentInstanceId", "operationId");
        JsonNode recovery = object(Map.of("attemptId", id, "claimToken", token, "inputRevision", id,
                "hostInstanceId", instance, "agentInstanceId", instance, "operationId", operation),
                "attemptId", "claimToken", "hostInstanceId", "agentInstanceId", "operationId");
        JsonNode present = object(Map.of("path", string(2048, true), "state", constant("PRESENT"), "contentVersion", string(71, true)), "path", "state", "contentVersion");
        JsonNode missing = object(Map.of("path", string(2048, true), "state", constant("MISSING")), "path", "state");
        ObjectNode observation = json.createObjectNode();
        observation.putArray("oneOf").add(present).add(missing);
        JsonNode observations = array(observation);
        JsonNode sourceObservations = array(object(Map.of("sourceAttemptId", id, "sourceInputRevision", id, "observations", observations),
                "sourceAttemptId", "sourceInputRevision", "observations"));
        JsonNode adoption = object(Map.of("reviewOperationId", operation, "observations", observations), "reviewOperationId", "observations");
        JsonNode condition = object(Map.of("condition", string(0, true), "result", string(0, true),
                "evidenceRefs", strings), "condition", "result", "evidenceRefs");
        JsonNode documentRef = object(Map.of("kind", constant("DOCUMENT"), "documentId", id, "documentVersion", id),
                "kind", "documentId", "documentVersion");
        JsonNode fileRef = object(Map.of("kind", constant("PROJECT_FILE"), "projectPath", string(2048, true), "contentVersion", string(71, true)),
                "kind", "projectPath", "contentVersion");
        ObjectNode contextRef = json.createObjectNode();
        contextRef.putArray("oneOf").add(documentRef).add(fileRef);
        JsonNode refs = array(contextRef);
        JsonNode evidence = object(Map.of("conditions", array(condition), "changedPaths", strings,
                "artifactRefs", strings, "projectCheckpoint", string(0, true), "contextRefs", refs, "prerequisiteAdoptions", array(adoption)),
                "conditions", "changedPaths", "artifactRefs", "contextRefs", "prerequisiteAdoptions");
        JsonNode confirmation = object(Map.of("stopped", bool(), "detail", string(2048, true)), "stopped", "detail");
        ObjectNode nullableVersion = json.createObjectNode();
        nullableVersion.putArray("oneOf").add(string(71, true)).add(json.createObjectNode().put("type", "null"));
        ObjectNode nullablePath = json.createObjectNode();
        nullablePath.putArray("oneOf").add(string(2048, true)).add(json.createObjectNode().put("type", "null"));
        JsonNode checkpoint = object(Map.of("occurredAt", string(64, true), "finding", string(0, false),
                "artifactRefs", strings, "blockedReason", string(0, false), "contextRefs", refs,
                "artifactObservations", observations, "previousContentVersion", nullableVersion, "previousPath", nullablePath),
                "occurredAt", "artifactRefs", "contextRefs", "artifactObservations", "previousContentVersion", "previousPath");

        define("save_project_device_binding", "保存项目设备绑定", "保存当前用户在设备上的项目根目录。",
                object(Map.of("projectId", id, "deviceId", device, "projectDirectory", directory), "projectId", "deviceId", "projectDirectory"), false, true);
        define("get_project_device_binding", "读取项目设备绑定", "读取当前用户的设备绑定。",
                object(Map.of("projectId", id, "deviceId", device), "projectId", "deviceId"), true, true);
        define("list_project_device_bindings", "读取项目设备绑定列表", "读取当前用户在项目各设备上的有效绑定。",
                object(Map.of("projectId", id), "projectId"), true, true);
        define("delete_project_device_binding", "删除项目设备绑定", "删除当前用户的设备绑定；未确认停止的执行会阻止删除。",
                object(Map.of("projectId", id, "deviceId", device), "projectId", "deviceId"), false, false);
        define("get_task_collaboration_inputs", "读取任务协作输入", "读取任务输入与前置依赖。",
                object(Map.of("taskKey", key), "taskKey"), true, true);
        define("observe_collaboration_environment", "观察协作环境", "读取前置成果与当前观察事实；已有交付待复核不阻止领取，观察会记录过期尝试的停止意图。",
                object(Map.of("batch", batch, "sourceObservations", sourceObservations), "batch", "sourceObservations"), false, false);
        define("claim_collaboration_task", "领取协作任务", "原子领取任务；相同 operationId 回放原结果。",
                object(Map.of("batch", batch, "taskId", id, "inputRevision", id, "hostInstanceId", instance,
                        "agentInstanceId", instance, "operationId", operation, "sourceObservations", sourceObservations),
                        "batch", "taskId", "inputRevision", "hostInstanceId", "agentInstanceId", "operationId", "sourceObservations"), false, true);
        define("list_collaboration_documents", "发现项目文档", "发现当前尝试可读取的项目文档。",
                object(Map.of("batch", batch, "taskId", id, "command", command), "batch", "taskId", "command"), true, true);
        define("read_collaboration_document", "读取项目文档", "返回来源内容及对应版本；读取不构成采用。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "documentId", id), "batch", "taskId", "command", "documentId"), true, true);
        define("adopt_collaboration_context", "记录上下文采用", "将来源版本记录为当前尝试事实，不改变任务输入。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "contextRefs", refs), "batch", "taskId", "command", "contextRefs"), false, true);
        JsonNode change = object(Map.of("action", string(32, true), "prerequisiteTaskId", id, "missing", string(0, true),
                "reason", string(0, true), "evidence", string(0, true), "goal", string(0, true), "impact", string(0, true)),
                "action", "missing", "reason", "evidence");
        define("change_collaboration_dependency", "记录缺少成果与协作关系", "当前尝试按观察事实建立或撤销关系；目标扩展不改变本批次授权。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "change", change), "batch", "taskId", "command", "change"), false, true);
        JsonNode subtask = object(Map.of("title", string(256, true), "description", string(8000, false)), "title");
        JsonNode split = object(Map.of("reason", string(2048, true), "subtasks", array(subtask)), "reason", "subtasks");
        define("split_collaboration_task", "拆分当前任务", "当前任务包含多个可独立验证的成果时，在本任务下创建子任务，只写需求与完成条件，不声明依赖；子任务并入本批授权集合，本次尝试随即停止，父任务由子任务汇总完成。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "split", split), "batch", "taskId", "command", "split"), false, true);
        JsonNode review = object(Map.of("sourceTaskId", id, "sourceAttemptId", id, "sourceInputRevision", id,
                "decision", string(32, true), "finding", string(2048, true), "evidence", string(2048, true),
                "observations", observations, "goal", string(2048, true), "impact", string(2048, true)),
                "sourceTaskId", "sourceAttemptId", "sourceInputRevision", "decision", "finding", "evidence", "observations");
        define("review_collaboration_prerequisite", "复核前置成果", "当前尝试记录读取、采用、目标内修复或请求本批上游返工。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "review", review), "batch", "taskId", "command", "review"), false, true);
        define("renew_collaboration_attempt", "续租执行尝试", "续期租约；过期或输入失效返回阻塞原因。",
                object(Map.of("batch", batch, "taskId", id, "command", command), "batch", "taskId", "command"), false, true);
        define("append_collaboration_checkpoint", "追加执行检查点", "追加发现、阻塞和成果引用；执行者与操作标识来自 command。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "checkpoint", checkpoint), "batch", "taskId", "command", "checkpoint"), false, true);
        define("complete_collaboration_task", "交付协作任务", "提交条件、修改位置、成果与停止事实；服务保存证据并完成任务。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "evidence", evidence,
                        "confirmation", confirmation), "batch", "taskId", "command", "evidence", "confirmation"), false, true);
        define("request_collaboration_stop", "请求停止执行", "按用户、设备和执行归属记录停止意图；支持过期尝试。",
                object(Map.of("batchToken", token, "projectId", id, "bindingId", id, "deviceId", device, "taskId", id,
                        "command", recovery, "reason", string(2048, true)), "batchToken", "projectId", "bindingId", "deviceId", "taskId", "command", "reason"), false, true);
        define("get_collaboration_recovery", "读取执行恢复事实", "按执行归属读取检查点及停止事实；支持过期批次。",
                object(Map.of("batchToken", token, "projectId", id, "bindingId", id, "deviceId", device, "taskId", id,
                        "command", recovery), "batchToken", "projectId", "bindingId", "deviceId", "taskId", "command"), true, true);
        define("recover_unacknowledged_claim", "恢复未确认领取并停止", "使用原签名批次及领取回执证明恢复停止；禁止续租或交付。",
                object(Map.of("batchToken", token, "bindingId", id, "deviceId", device, "taskId", id,
                        "inputRevision", id, "hostInstanceId", instance, "agentInstanceId", instance, "claimOperationId", operation,
                        "operationId", operation, "confirmation", confirmation), "batchToken", "bindingId", "deviceId", "taskId",
                        "inputRevision", "hostInstanceId", "agentInstanceId", "claimOperationId", "operationId", "confirmation"), false, true);
        define("confirm_collaboration_stop", "确认执行停止", "宿主确认关联执行与写入进程停止；支持过期尝试。",
                object(Map.of("batchToken", token, "projectId", id, "bindingId", id, "deviceId", device, "taskId", id,
                        "command", recovery, "confirmation", confirmation), "batchToken", "projectId", "bindingId", "deviceId", "taskId", "command", "confirmation"), false, true);
    }

    private void define(String name, String title, String description, JsonNode schema, boolean readOnly, boolean idempotent) {
        definitions.put(name, new Definition(name, title, description, schema, readOnly, idempotent));
    }

    private ObjectNode object(Map<String, JsonNode> fields, String... required) {
        ObjectNode node = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = node.putObject("properties");
        fields.forEach(properties::set);
        ArrayNode names = node.putArray("required");
        for (String name : required) names.add(name);
        return node;
    }

    private ObjectNode constant(String value) { return json.createObjectNode().put("type", "string").put("const", value); }

    private ObjectNode array(JsonNode items) { ObjectNode node = json.createObjectNode().put("type", "array"); node.set("items", items); return node; }
    private ObjectNode integer() { return json.createObjectNode().put("type", "integer").put("minimum", 1); }
    private ObjectNode bool() { return json.createObjectNode().put("type", "boolean"); }
    private ObjectNode string(int maxLength, boolean requiredContent) {
        ObjectNode node = json.createObjectNode().put("type", "string");
        if (maxLength > 0) node.put("maxLength", maxLength);
        if (requiredContent) node.put("minLength", 1);
        return node;
    }

    /** 同一 schema 供发现与两类入口校验；错误不包含调用方原文。 */
    private void validate(JsonNode value, JsonNode schema) {
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode candidate : schema.path("oneOf")) {
                try { validate(value, candidate); matches++; }
                catch (ParameterException ignored) { }
            }
            if (matches != 1) throw new ParameterException("来源类型或字段无效");
            return;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) throw new ParameterException("协作参数取值无效");
        String type = schema.path("type").asText();
        if ("null".equals(type)) {
            if (value == null || !value.isNull()) throw new ParameterException("协作参数类型无效");
            return;
        }
        if (value == null || value.isNull()) throw new ParameterException("协作参数缺少必填值");
        switch (type) {
            case "object" -> {
                if (!value.isObject()) throw new ParameterException("协作参数类型无效");
                JsonNode properties = schema.path("properties");
                var names = value.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!properties.has(name)) throw new ParameterException("协作参数包含未知字段");
                    validate(value.get(name), properties.get(name));
                }
                for (JsonNode required : schema.path("required"))
                    if (!value.has(required.textValue())) throw new ParameterException("协作参数缺少必填值");
            }
            case "array" -> {
                if (!value.isArray()) throw new ParameterException("协作参数类型无效");
                for (JsonNode item : value) validate(item, schema.path("items"));
            }
            case "string" -> {
                if (!value.isTextual() || (schema.has("maxLength") && value.textValue().length() > schema.path("maxLength").asInt())
                        || (schema.has("minLength") && value.textValue().isBlank()))
                    throw new ParameterException("协作参数类型或长度无效");
            }
            case "integer" -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
                    throw new ParameterException("协作参数类型或范围无效");
            }
            case "boolean" -> {
                if (!value.isBoolean()) throw new ParameterException("协作参数类型无效");
            }
            default -> throw new IllegalStateException("协作参数定义无效");
        }
    }

    public record Definition(String name, String title, String description, JsonNode inputSchema,
                             boolean readOnly, boolean idempotent) { }
    public static class ParameterException extends IllegalArgumentException {
        public ParameterException(String message) { super(message); }
    }
}
