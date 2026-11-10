package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.dto.TaskCollaborationInputs;
import com.linearlite.server.dto.TaskDeliveryEvidence;
import com.linearlite.server.dto.TaskExecutionCheckpoint;
import com.linearlite.server.exception.UnauthorizedException;
import java.time.Instant;
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
    private final CollaborationBatchAuthorizationService batches;
    private final TaskCollaborationInputService inputs;
    private final TaskCollaborationClaimService claims;
    private final TaskExecutionLifecycleService lifecycle;
    private final TaskCollaborationCompletionService completion;
    private final UnacknowledgedClaimRecoveryService unacknowledgedClaims;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    public CollaborationApiService(ObjectMapper json, ProjectDeviceBindingService bindings,
            CollaborationBatchAuthorizationService batches, TaskCollaborationInputService inputs,
            TaskCollaborationClaimService claims, TaskExecutionLifecycleService lifecycle,
            TaskCollaborationCompletionService completion, UnacknowledgedClaimRecoveryService unacknowledgedClaims) {
        this.json = json;
        this.bindings = bindings;
        this.batches = batches;
        this.inputs = inputs;
        this.claims = claims;
        this.lifecycle = lifecycle;
        this.completion = completion;
        this.unacknowledgedClaims = unacknowledgedClaims;
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
            case "issue_collaboration_batch" -> batches.issue(userId, number(request, "projectId"),
                    number(request, "bindingId"), text(request, "deviceId"), text(request, "projectDirectory"),
                    longList(request.get("taskIds")), instant(request, "expiresAt"));
            case "replace_task_collaboration_inputs" -> inputs.replaceInputs(text(request, "taskKey"),
                    convert(request.get("inputs"), TaskCollaborationInputs.class), userId);
            case "get_task_collaboration_inputs" -> inputs.getInputs(text(request, "taskKey"), userId);
            case "observe_collaboration_environment" -> claims.observe(userId, batch(request), available(request));
            case "claim_collaboration_task" -> claims.claim(userId, batch(request), number(request, "taskId"),
                    number(request, "inputRevision"), text(request, "hostInstanceId"), text(request, "agentInstanceId"),
                    text(request, "operationId"), available(request));
            case "expand_collaboration_write_scopes" -> claims.expand(userId, batch(request), number(request, "taskId"),
                    command(request), stringList(request.get("requestedScopes")));
            case "renew_collaboration_attempt" -> lifecycle.renew(userId, batch(request), number(request, "taskId"), command(request));
            case "append_collaboration_checkpoint" -> checkpoint(userId, request);
            case "complete_collaboration_task" -> completion.complete(userId, batch(request), number(request, "taskId"),
                    command(request), convert(request.get("evidence"), TaskDeliveryEvidence.class),
                    convert(request.get("confirmation"), CollaborationStopConfirmation.class));
            case "get_collaboration_recovery" -> lifecycle.readRecovery(userId, number(request, "projectId"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"), command(request));
            case "recover_unacknowledged_claim" -> unacknowledgedClaims.recover(userId, text(request, "batchToken"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"),
                    text(request, "hostInstanceId"), text(request, "agentInstanceId"), text(request, "claimOperationId"),
                    text(request, "operationId"), convert(request.get("confirmation"), CollaborationStopConfirmation.class));
            case "request_collaboration_stop" -> lifecycle.requestStop(userId, number(request, "projectId"),
                    number(request, "bindingId"), text(request, "deviceId"), number(request, "taskId"),
                    command(request), text(request, "reason"));
            case "confirm_collaboration_stop" -> lifecycle.confirmStop(userId, number(request, "projectId"),
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
                optionalText(item, "blockedReason"));
        return lifecycle.checkpoint(userId, batch(request), number(request, "taskId"), command, fact);
    }

    private CollaborationBatchRequest batch(JsonNode request) {
        return convert(request.get("batch"), CollaborationBatchRequest.class);
    }

    private CollaborationAttemptCommand command(JsonNode request) {
        return convert(request.get("command"), CollaborationAttemptCommand.class);
    }

    private List<TaskCollaborationClaimService.AvailableResult> available(JsonNode request) {
        List<TaskCollaborationClaimService.AvailableResult> result = new ArrayList<>();
        for (JsonNode value : request.get("available"))
            result.add(convert(value, TaskCollaborationClaimService.AvailableResult.class));
        return result;
    }

    private List<Long> longList(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(value -> result.add(value.longValue()));
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

    private Instant instant(JsonNode value, String field) {
        try { return Instant.parse(text(value, field)); }
        catch (DateTimeParseException e) { throw new ParameterException("协作时间参数无效"); }
    }

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
        JsonNode available = array(object(Map.of("attemptId", id, "inputRevision", id), "attemptId", "inputRevision"));
        JsonNode reference = object(Map.of("documentId", id, "version", id), "documentId", "version");
        JsonNode collaborationInputs = object(Map.of("inputRevision", id, "agentExecutionEnabled", bool(),
                "writeScopes", strings, "publicContextRefs", array(reference), "prerequisiteTaskIds", array(id)),
                "inputRevision", "agentExecutionEnabled", "writeScopes", "publicContextRefs", "prerequisiteTaskIds");
        JsonNode condition = object(Map.of("condition", string(0, true), "result", string(0, true),
                "evidenceRefs", strings), "condition", "result", "evidenceRefs");
        JsonNode evidence = object(Map.of("conditions", array(condition), "changedPaths", strings,
                "artifactRefs", strings, "projectCheckpoint", string(0, true)),
                "conditions", "changedPaths", "artifactRefs", "projectCheckpoint");
        JsonNode confirmation = object(Map.of("stopped", bool(), "detail", string(2048, true)), "stopped", "detail");
        JsonNode checkpoint = object(Map.of("occurredAt", string(64, true), "finding", string(0, false),
                "artifactRefs", strings, "blockedReason", string(0, false)), "occurredAt", "artifactRefs");

        define("save_project_device_binding", "保存项目设备绑定", "保存当前用户在设备上的项目根目录。",
                object(Map.of("projectId", id, "deviceId", device, "projectDirectory", directory), "projectId", "deviceId", "projectDirectory"), false, true);
        define("get_project_device_binding", "读取项目设备绑定", "读取当前用户的设备绑定。",
                object(Map.of("projectId", id, "deviceId", device), "projectId", "deviceId"), true, true);
        define("list_project_device_bindings", "读取项目设备绑定列表", "读取当前用户在项目各设备上的有效绑定。",
                object(Map.of("projectId", id), "projectId"), true, true);
        define("delete_project_device_binding", "删除项目设备绑定", "删除当前用户的设备绑定；未确认停止的执行会阻止删除。",
                object(Map.of("projectId", id, "deviceId", device), "projectId", "deviceId"), false, false);
        define("issue_collaboration_batch", "签发协作批次", "固定绑定、设备、任务范围与有效期；返回 batchToken。",
                object(Map.of("projectId", id, "bindingId", id, "deviceId", device, "projectDirectory", directory,
                        "taskIds", array(id), "expiresAt", string(64, true)), "projectId", "bindingId", "deviceId", "projectDirectory", "taskIds", "expiresAt"), false, false);
        define("replace_task_collaboration_inputs", "替换任务协作输入", "按输入版本更新授权、范围、公共约定与前置任务。",
                object(Map.of("taskKey", key, "inputs", collaborationInputs), "taskKey", "inputs"), false, true);
        define("get_task_collaboration_inputs", "读取任务协作输入", "读取任务输入与前置依赖。",
                object(Map.of("taskKey", key), "taskKey"), true, true);
        define("observe_collaboration_environment", "观察协作环境", "声明宿主可用的前置尝试和版本；返回就绪、阻塞与资源归属。观察会记录过期尝试的停止意图。",
                object(Map.of("batch", batch, "available", available), "batch", "available"), false, false);
        define("claim_collaboration_task", "领取协作任务", "原子领取任务与范围；相同 operationId 回放原结果。",
                object(Map.of("batch", batch, "taskId", id, "inputRevision", id, "hostInstanceId", instance,
                        "agentInstanceId", instance, "operationId", operation, "available", available),
                        "batch", "taskId", "inputRevision", "hostInstanceId", "agentInstanceId", "operationId", "available"), false, true);
        define("expand_collaboration_write_scopes", "扩张写入范围", "申请额外范围；冲突返回停止意图和原范围。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "requestedScopes", strings), "batch", "taskId", "command", "requestedScopes"), false, true);
        define("renew_collaboration_attempt", "续租执行尝试", "续期租约；过期或输入失效返回阻塞原因。",
                object(Map.of("batch", batch, "taskId", id, "command", command), "batch", "taskId", "command"), false, true);
        define("append_collaboration_checkpoint", "追加执行检查点", "追加发现、阻塞和成果引用；执行者与操作标识来自 command。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "checkpoint", checkpoint), "batch", "taskId", "command", "checkpoint"), false, true);
        define("complete_collaboration_task", "交付协作任务", "提交条件、修改位置、成果与停止事实；服务保存证据并完成任务。",
                object(Map.of("batch", batch, "taskId", id, "command", command, "evidence", evidence,
                        "confirmation", confirmation), "batch", "taskId", "command", "evidence", "confirmation"), false, true);
        define("request_collaboration_stop", "请求停止执行", "按用户、设备和执行归属记录停止意图；支持过期尝试。",
                object(Map.of("projectId", id, "bindingId", id, "deviceId", device, "taskId", id,
                        "command", recovery, "reason", string(2048, true)), "projectId", "bindingId", "deviceId", "taskId", "command", "reason"), false, true);
        define("get_collaboration_recovery", "读取执行恢复事实", "按执行归属读取范围、检查点及停止事实；支持过期批次。",
                object(Map.of("projectId", id, "bindingId", id, "deviceId", device, "taskId", id,
                        "command", recovery), "projectId", "bindingId", "deviceId", "taskId", "command"), true, true);
        define("recover_unacknowledged_claim", "恢复未确认领取并停止", "使用原签名批次及领取回执证明恢复停止；禁止续租或交付。",
                object(Map.of("batchToken", token, "bindingId", id, "deviceId", device, "taskId", id,
                        "hostInstanceId", instance, "agentInstanceId", instance, "claimOperationId", operation,
                        "operationId", operation, "confirmation", confirmation), "batchToken", "bindingId", "deviceId", "taskId",
                        "hostInstanceId", "agentInstanceId", "claimOperationId", "operationId", "confirmation"), false, true);
        define("confirm_collaboration_stop", "确认执行停止", "宿主确认关联执行与写入进程停止；支持过期尝试。",
                object(Map.of("projectId", id, "bindingId", id, "deviceId", device, "taskId", id,
                        "command", recovery, "confirmation", confirmation), "projectId", "bindingId", "deviceId", "taskId", "command", "confirmation"), false, true);
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
        String type = schema.path("type").asText();
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
