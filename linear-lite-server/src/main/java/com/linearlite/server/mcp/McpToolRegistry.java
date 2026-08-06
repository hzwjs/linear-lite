package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectService;
import com.linearlite.server.service.TaskCommandService;
import com.linearlite.server.service.TaskCommentService;
import com.linearlite.server.service.TaskQueryService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP 工具目录与领域服务映射；工具顺序固定以保证 tools/list 可缓存。 */
@Component
public class McpToolRegistry {

    private static final Set<String> STATUSES = Set.of(
            "backlog", "todo", "in_progress", "in_review", "done", "canceled", "duplicate");
    private static final Set<String> PRIORITIES = Set.of("urgent", "high", "medium", "low");

    private final ObjectMapper objectMapper;
    private final MarkdownToBlockNoteConverter markdownToBlockNoteConverter;
    private final ProjectService projectService;
    private final TaskCommandService taskCommandService;
    private final TaskQueryService taskQueryService;
    private final TaskCommentService taskCommentService;
    private final ProjectDocumentCommandService projectDocumentCommandService;
    private final Map<String, ToolHandler> handlers;
    private final List<ObjectNode> definitions;

    public McpToolRegistry(
            ObjectMapper objectMapper,
            MarkdownToBlockNoteConverter markdownToBlockNoteConverter,
            ProjectService projectService,
            TaskCommandService taskCommandService,
            TaskQueryService taskQueryService,
            TaskCommentService taskCommentService,
            ProjectDocumentCommandService projectDocumentCommandService) {
        this.objectMapper = objectMapper;
        this.markdownToBlockNoteConverter = markdownToBlockNoteConverter;
        this.projectService = projectService;
        this.taskCommandService = taskCommandService;
        this.taskQueryService = taskQueryService;
        this.taskCommentService = taskCommentService;
        this.projectDocumentCommandService = projectDocumentCommandService;

        Map<String, ToolHandler> registered = new LinkedHashMap<>();
        registered.put("list_projects", this::listProjects);
        registered.put("create_project", this::createProject);
        registered.put("create_task", this::createTask);
        registered.put("update_task", this::updateTask);
        registered.put("add_task_comment", this::addTaskComment);
        registered.put("get_task", this::getTask);
        registered.put("create_document", this::createDocument);
        registered.put("update_document", this::updateDocument);
        this.handlers = Map.copyOf(registered);
        this.definitions = List.of(
                listProjectsDefinition(),
                createProjectDefinition(),
                createTaskDefinition(),
                updateTaskDefinition(),
                addTaskCommentDefinition(),
                getTaskDefinition(),
                createDocumentDefinition(),
                updateDocumentDefinition());
    }

    public ArrayNode definitions() {
        ArrayNode result = objectMapper.createArrayNode();
        for (ObjectNode definition : definitions) {
            result.add(definition.deepCopy());
        }
        return result;
    }

    public JsonNode invoke(String name, JsonNode arguments, Long userId) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new McpToolNotFoundException("工具不存在: " + name);
        }
        return objectMapper.valueToTree(handler.handle(arguments, userId));
    }

    private Object createProject(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "name", "identifier");
        String name = McpArgumentValidator.requiredText(arguments, "name", 256);
        String identifier = McpArgumentValidator.requiredText(arguments, "identifier", 32);
        return projectService.create(name, identifier, userId);
    }

    private Object listProjects(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments);
        return projectService.list(userId);
    }

    private Object createTask(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "projectId", "parentId", "title", "description", "status",
                "priority", "assigneeId", "dueDate", "plannedStartDate", "progressPercent", "labels");
        Long projectId = requiredLong(arguments, "projectId");
        Long parentId = McpArgumentValidator.optionalLong(arguments, "parentId");
        String title = McpArgumentValidator.requiredText(arguments, "title", 256);
        String description = McpArgumentValidator.optionalText(arguments, "description", 100_000);
        String status = optionalEnum(arguments, "status", STATUSES);
        String priority = optionalEnum(arguments, "priority", PRIORITIES);
        Long assigneeId = McpArgumentValidator.optionalLong(arguments, "assigneeId");
        LocalDateTime dueDate = McpArgumentValidator.optionalDateTime(arguments, "dueDate");
        LocalDateTime plannedStartDate = McpArgumentValidator.optionalDateTime(arguments, "plannedStartDate");
        Integer progress = McpArgumentValidator.optionalInteger(arguments, "progressPercent", 0, 100);
        List<TaskLabelItemRequest> labels = McpArgumentValidator.optionalLabels(arguments, "labels");
        return taskCommandService.create(
                projectId, userId, parentId, title, description, status, priority, assigneeId,
                dueDate, plannedStartDate, progress, labels);
    }

    private Object updateTask(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "taskKey", "title", "parentId", "clearParent", "description",
                "status", "priority", "assigneeId", "clearAssignee", "dueDate", "clearDueDate",
                "plannedStartDate", "clearPlannedStart", "progressPercent", "labels");
        rejectBoth(arguments, "parentId", "clearParent");
        rejectBoth(arguments, "assigneeId", "clearAssignee");
        rejectBoth(arguments, "dueDate", "clearDueDate");
        rejectBoth(arguments, "plannedStartDate", "clearPlannedStart");

        String taskKey = McpArgumentValidator.requiredText(arguments, "taskKey", 32);
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle(McpArgumentValidator.optionalText(arguments, "title", 256));
        request.setParentId(McpArgumentValidator.optionalLong(arguments, "parentId"));
        request.setClearParent(McpArgumentValidator.optionalBoolean(arguments, "clearParent"));
        request.setDescription(McpArgumentValidator.optionalText(arguments, "description", 100_000));
        request.setStatus(optionalEnum(arguments, "status", STATUSES));
        request.setPriority(optionalEnum(arguments, "priority", PRIORITIES));
        request.setAssigneeId(McpArgumentValidator.optionalLong(arguments, "assigneeId"));
        request.setClearAssignee(McpArgumentValidator.optionalBoolean(arguments, "clearAssignee"));
        request.setDueDate(McpArgumentValidator.optionalDateTime(arguments, "dueDate"));
        request.setClearDueDate(McpArgumentValidator.optionalBoolean(arguments, "clearDueDate"));
        request.setPlannedStartDate(McpArgumentValidator.optionalDateTime(arguments, "plannedStartDate"));
        request.setClearPlannedStart(McpArgumentValidator.optionalBoolean(arguments, "clearPlannedStart"));
        request.setProgressPercent(McpArgumentValidator.optionalInteger(arguments, "progressPercent", 0, 100));
        request.setLabels(McpArgumentValidator.optionalLabels(arguments, "labels"));
        return taskCommandService.update(taskKey, request, userId);
    }

    private Object addTaskComment(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "taskKey", "body", "parentId", "mentionedUserIds");
        String taskKey = McpArgumentValidator.requiredText(arguments, "taskKey", 32);
        String body = McpArgumentValidator.requiredText(arguments, "body", 100_000);
        CreateTaskCommentRequest request = new CreateTaskCommentRequest();
        request.setBody(body);
        request.setParentId(McpArgumentValidator.optionalLong(arguments, "parentId"));
        List<Long> mentionedUserIds = McpArgumentValidator.optionalLongList(arguments, "mentionedUserIds", 100);
        if (mentionedUserIds != null) {
            request.setMentionedUserIds(mentionedUserIds);
        }
        return taskCommentService.create(taskKey, userId, request);
    }

    private Object getTask(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "taskKey");
        String taskKey = McpArgumentValidator.requiredText(arguments, "taskKey", 32);
        return taskQueryService.getByKeyOrThrow(taskKey, userId);
    }

    private Object createDocument(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "projectId", "parentDocumentId", "title", "content",
                "externalSource", "externalSourceId");
        Long projectId = requiredLong(arguments, "projectId");
        Long parentDocumentId = McpArgumentValidator.optionalLong(arguments, "parentDocumentId");
        String title = McpArgumentValidator.requiredText(arguments, "title", 256);
        String markdown = McpArgumentValidator.optionalText(arguments, "content", 2_000_000);
        String externalSource = McpArgumentValidator.optionalText(arguments, "externalSource", 64);
        String externalSourceId = McpArgumentValidator.optionalText(arguments, "externalSourceId", 128);
        return projectDocumentCommandService.create(
                projectId,
                new CreateProjectDocumentRequest(parentDocumentId, title,
                        markdown == null ? null : markdownToBlockNoteConverter.convert(markdown),
                        externalSource, externalSourceId),
                userId);
    }

    private Object updateDocument(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "documentId", "expectedVersion", "title", "content");
        Long documentId = requiredLong(arguments, "documentId");
        Long expectedVersion = requiredLong(arguments, "expectedVersion");
        String title = McpArgumentValidator.requiredText(arguments, "title", 256);
        String markdown = McpArgumentValidator.requiredText(arguments, "content", 2_000_000);
        return projectDocumentCommandService.update(
                documentId,
                new UpdateProjectDocumentRequest(
                        expectedVersion, title, markdownToBlockNoteConverter.convert(markdown)), userId);
    }

    private static Long requiredLong(JsonNode object, String name) {
        Long value = McpArgumentValidator.optionalLong(object, name);
        if (value == null) {
            throw new McpInvalidParamsException(name + " 必须是整数");
        }
        return value;
    }

    private static String optionalEnum(JsonNode object, String name, Set<String> allowed) {
        String value = McpArgumentValidator.optionalText(object, name, 32);
        if (value != null && !allowed.contains(value)) {
            throw new McpInvalidParamsException(name + " 不支持的值: " + value);
        }
        return value;
    }

    private static void rejectBoth(JsonNode arguments, String valueName, String clearName) {
        if (arguments.has(valueName) && arguments.has(clearName)) {
            throw new McpInvalidParamsException(valueName + " 与 " + clearName + " 不能同时提供");
        }
    }

    private ObjectNode createProjectDefinition() {
        return tool("create_project", "创建项目", "创建一个项目并将当前认证用户设置为项目创建者。",
                objectSchema(Map.of(
                        "name", stringSchema("项目名称", 1, 256),
                        "identifier", stringSchema("项目标识，例如 ENG", 1, 32)),
                        "name", "identifier"), false, false);
    }

    private ObjectNode listProjectsDefinition() {
        return tool("list_projects", "查询项目列表", "获取当前认证用户可访问的全部项目，返回结果保持项目排序。",
                objectSchema(), true, true);
    }

    private ObjectNode createTaskDefinition() {
        Map<String, ObjectNode> properties = taskCommonProperties();
        properties.put("projectId", integerSchema("项目 ID"));
        properties.put("parentId", integerSchema("父任务数据库 ID"));
        properties.put("labels", labelsSchema());
        return tool("create_task", "创建任务", "在指定项目中创建任务，creatorId 取自当前认证用户。",
                objectSchema(properties, "projectId", "title"), false, false);
    }

    private ObjectNode updateTaskDefinition() {
        Map<String, ObjectNode> properties = taskCommonProperties();
        properties.remove("projectId");
        properties.put("taskKey", stringSchema("对外任务 ID，例如 ENG-1", 1, 32));
        properties.put("parentId", integerSchema("新的父任务数据库 ID"));
        properties.put("clearParent", booleanSchema("是否解除父子关系"));
        properties.put("clearAssignee", booleanSchema("是否清空负责人"));
        properties.put("clearDueDate", booleanSchema("是否清空截止时间"));
        properties.put("clearPlannedStart", booleanSchema("是否清空计划开始时间"));
        properties.put("labels", labelsSchema());
        ObjectNode schema = objectSchema(properties, "taskKey");
        schema.set("allOf", objectMapper.createArrayNode());
        // 服务端同时校验互斥字段，schema 负责向 MCP 客户端声明更新语义。
        addMutualExclusion(schema, "parentId", "clearParent");
        addMutualExclusion(schema, "assigneeId", "clearAssignee");
        addMutualExclusion(schema, "dueDate", "clearDueDate");
        addMutualExclusion(schema, "plannedStartDate", "clearPlannedStart");
        return tool("update_task", "更新任务", "按 taskKey 更新任务；未提供的字段保持不变。",
                schema, false, false);
    }

    private ObjectNode addTaskCommentDefinition() {
        return tool("add_task_comment", "发表评论", "向指定任务发表评论，可创建评论回复并 @ 项目成员。",
                objectSchema(Map.of(
                        "taskKey", stringSchema("对外任务 ID，例如 ENG-1", 1, 32),
                        "body", stringSchema("评论正文", 1, 100_000),
                        "parentId", integerSchema("父评论 ID"),
                        "mentionedUserIds", integerArraySchema("被提及的项目成员 ID", 100)),
                        "taskKey", "body"), false, false);
    }

    private ObjectNode getTaskDefinition() {
        return tool("get_task", "获取任务信息", "按对外任务 ID 获取任务详情。",
                objectSchema(Map.of("taskKey", stringSchema("对外任务 ID，例如 ENG-1", 1, 32)), "taskKey"),
                true, true);
    }

    private ObjectNode createDocumentDefinition() {
        return tool("create_document", "创建文档", "在项目文档树中创建 Markdown 文档，服务端负责转换为 BlockNote JSON。",
                objectSchema(Map.of(
                        "projectId", integerSchema("项目 ID"),
                        "parentDocumentId", integerSchema("父文档 ID"),
                        "title", stringSchema("文档标题", 1, 256),
                        "content", stringSchema("Markdown 正文", 0, 2_000_000),
                        "externalSource", stringSchema("外部来源类型", 1, 64),
                        "externalSourceId", stringSchema("外部来源文档 ID", 1, 128)), "projectId", "title"),
                false, false);
    }

    private ObjectNode updateDocumentDefinition() {
        return tool("update_document", "更新文档", "按文档版本号安全更新标题和 Markdown 正文，服务端负责转换为 BlockNote JSON。",
                objectSchema(Map.of(
                        "documentId", integerSchema("文档 ID"),
                        "expectedVersion", integerSchema("客户端已读版本号"),
                        "title", stringSchema("文档标题", 1, 256),
                        "content", stringSchema("Markdown 正文", 1, 2_000_000)),
                        "documentId", "expectedVersion", "title", "content"), false, false);
    }

    private Map<String, ObjectNode> taskCommonProperties() {
        Map<String, ObjectNode> properties = new LinkedHashMap<>();
        properties.put("projectId", integerSchema("项目 ID"));
        properties.put("title", stringSchema("任务标题", 1, 256));
        properties.put("description", stringSchema("任务描述", 0, 100_000));
        properties.put("status", enumSchema("任务状态", STATUSES));
        properties.put("priority", enumSchema("任务优先级", PRIORITIES));
        properties.put("assigneeId", integerSchema("负责人用户 ID"));
        properties.put("dueDate", dateTimeSchema("截止时间"));
        properties.put("plannedStartDate", dateTimeSchema("计划开始时间"));
        properties.put("progressPercent", integerRangeSchema("完成进度", 0, 100));
        return properties;
    }

    private ObjectNode tool(String name, String title, String description, ObjectNode inputSchema,
                            boolean readOnly, boolean idempotent) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        tool.set("outputSchema", outputSchema());
        ObjectNode annotations = tool.putObject("annotations");
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", false);
        annotations.put("idempotentHint", idempotent);
        annotations.put("openWorldHint", false);
        return tool;
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode outputSchema() {
        // 领域 DTO 会随具体工具返回不同字段，输出 schema 只声明 JSON 对象而不错误限制字段集合。
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    private ObjectNode objectSchema(Map<String, ObjectNode> properties, String... required) {
        ObjectNode schema = objectSchema();
        ObjectNode propertyNode = schema.putObject("properties");
        properties.forEach(propertyNode::set);
        if (required.length > 0) {
            ArrayNode requiredNode = schema.putArray("required");
            for (String name : required) {
                requiredNode.add(name);
            }
        }
        return schema;
    }

    private ObjectNode stringSchema(String description, int minLength, int maxLength) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("minLength", minLength);
        schema.put("maxLength", maxLength);
        return schema;
    }

    private ObjectNode integerSchema(String description) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private ObjectNode integerRangeSchema(String description, int min, int max) {
        ObjectNode schema = integerSchema(description);
        schema.put("minimum", min);
        schema.put("maximum", max);
        return schema;
    }

    private ObjectNode integerArraySchema(String description, int maxItems) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("maxItems", maxItems);
        schema.set("items", integerSchema("数组元素"));
        return schema;
    }

    private ObjectNode labelsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "array");
        schema.put("maxItems", 100);
        ObjectNode item = objectSchema();
        ObjectNode properties = item.putObject("properties");
        properties.set("id", integerSchema("已有标签 ID"));
        properties.set("name", stringSchema("标签名称", 1, 64));
        item.set("oneOf", objectMapper.createArrayNode()
                .add(objectSchema(Map.of("id", integerSchema("已有标签 ID")), "id"))
                .add(objectSchema(Map.of("name", stringSchema("标签名称", 1, 64)), "name")));
        schema.set("items", item);
        return schema;
    }

    private ObjectNode dateTimeSchema(String description) {
        ObjectNode schema = stringSchema(description, 1, 64);
        schema.put("format", "date-time");
        return schema;
    }

    private ObjectNode booleanSchema(String description) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }

    private ObjectNode enumSchema(String description, Set<String> values) {
        ObjectNode schema = stringSchema(description, 1, 32);
        ArrayNode enumNode = schema.putArray("enum");
        values.stream().sorted().forEach(enumNode::add);
        return schema;
    }

    private void addMutualExclusion(ObjectNode schema, String first, String second) {
        ObjectNode exclusion = objectMapper.createObjectNode();
        ObjectNode forbidden = exclusion.putObject("not");
        ArrayNode required = forbidden.putArray("required");
        required.add(first);
        required.add(second);
        schema.withArray("allOf").add(exclusion);
    }

    @FunctionalInterface
    interface ToolHandler {
        Object handle(JsonNode arguments, Long userId);
    }

    public static class McpToolNotFoundException extends RuntimeException {
        public McpToolNotFoundException(String message) {
            super(message);
        }
    }
}
