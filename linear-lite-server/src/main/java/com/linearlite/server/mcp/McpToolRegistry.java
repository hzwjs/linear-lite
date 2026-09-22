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
import com.linearlite.server.service.ProjectDocumentQueryService;
import com.linearlite.server.service.ProjectService;
import com.linearlite.server.service.TaskCommandService;
import com.linearlite.server.service.TaskCommentService;
import com.linearlite.server.service.TaskQueryService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** MCP 工具目录与领域服务映射；工具顺序固定以保证 tools/list 可缓存。 */
@Component
public class McpToolRegistry {

    /** 让 MCP 客户端在工具目录层就能得到唯一的 Linear Lite 调用入口。 */
    private static final String TOOL_USAGE_PREFIX =
            "用于 Linear Lite（用户通常简称为 Linear）的项目管理。当用户要求创建、查询或更新 Linear 任务或项目文档时，必须优先调用此工具；不要使用浏览器，也不要寻找其他 mcp__linear_lite_* 工具。";

    private static final Set<String> STATUSES = Set.of(
            "backlog", "todo", "in_progress", "in_review", "done", "canceled", "duplicate");
    private static final Set<String> PRIORITIES = Set.of("urgent", "high", "medium", "low");
    private static final Pattern IMAGE_REF_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_DOCUMENT_IMAGES = 8;
    private static final int MAX_IMAGE_BASE64_CHARS = 8_388_608;
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BASE64_CHARS = 4 * ((MAX_TOTAL_IMAGE_BYTES + 2) / 3);

    private final ObjectMapper objectMapper;
    private final McpDocumentImageContentService documentImageContentService;
    private final ProjectService projectService;
    private final TaskCommandService taskCommandService;
    private final TaskQueryService taskQueryService;
    private final TaskCommentService taskCommentService;
    private final ProjectDocumentCommandService projectDocumentCommandService;
    private final ProjectDocumentQueryService projectDocumentQueryService;
    private final Map<String, ToolHandler> handlers;
    private final List<ObjectNode> definitions;

    public McpToolRegistry(
            ObjectMapper objectMapper,
            McpDocumentImageContentService documentImageContentService,
            ProjectService projectService,
            TaskCommandService taskCommandService,
            TaskQueryService taskQueryService,
            TaskCommentService taskCommentService,
            ProjectDocumentCommandService projectDocumentCommandService,
            ProjectDocumentQueryService projectDocumentQueryService) {
        this.objectMapper = objectMapper;
        this.documentImageContentService = documentImageContentService;
        this.projectService = projectService;
        this.taskCommandService = taskCommandService;
        this.taskQueryService = taskQueryService;
        this.taskCommentService = taskCommentService;
        this.projectDocumentCommandService = projectDocumentCommandService;
        this.projectDocumentQueryService = projectDocumentQueryService;

        Map<String, ToolHandler> registered = new LinkedHashMap<>();
        registered.put("list_projects", this::listProjects);
        registered.put("create_project", this::createProject);
        registered.put("create_task", this::createTask);
        registered.put("update_task", this::updateTask);
        registered.put("add_task_comment", this::addTaskComment);
        registered.put("get_task", this::getTask);
        registered.put("list_documents", this::listDocuments);
        registered.put("get_document", this::getDocument);
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
                listDocumentsDefinition(),
                getDocumentDefinition(),
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
        LocalDate dueDate = McpArgumentValidator.optionalDate(arguments, "dueDate");
        LocalDate plannedStartDate = McpArgumentValidator.optionalDate(arguments, "plannedStartDate");
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
        request.setDueDate(McpArgumentValidator.optionalDate(arguments, "dueDate"));
        request.setClearDueDate(McpArgumentValidator.optionalBoolean(arguments, "clearDueDate"));
        request.setPlannedStartDate(McpArgumentValidator.optionalDate(arguments, "plannedStartDate"));
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

    private Object listDocuments(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "projectId", "archived");
        Long projectId = requiredLong(arguments, "projectId");
        Boolean archived = McpArgumentValidator.optionalBoolean(arguments, "archived");
        // 列表只走树投影，正文读取必须显式调用 get_document，避免批量加载 LONGTEXT 内容。
        return projectDocumentQueryService.listTree(projectId, userId, Boolean.TRUE.equals(archived));
    }

    private Object getDocument(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "documentId", "title");
        Long documentId = McpArgumentValidator.optionalLong(arguments, "documentId");
        String title = McpArgumentValidator.optionalText(arguments, "title", 256);
        if ((documentId == null) == (title == null)) {
            throw new McpInvalidParamsException("documentId 与 title 必须且只能提供一个");
        }
        // 查询服务统一执行项目成员权限校验，并返回版本号供 update_document 使用。
        if (documentId != null) {
            return projectDocumentQueryService.getDocument(documentId, userId);
        }
        return projectDocumentQueryService.getDocumentByTitle(title, userId);
    }

    private Object createDocument(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "projectId", "parentDocumentId", "title", "content",
                "externalSource", "externalSourceId", "images");
        Long projectId = requiredLong(arguments, "projectId");
        Long parentDocumentId = McpArgumentValidator.optionalLong(arguments, "parentDocumentId");
        String title = McpArgumentValidator.requiredText(arguments, "title", 256);
        String markdown = McpArgumentValidator.optionalText(arguments, "content", 2_000_000);
        String externalSource = McpArgumentValidator.optionalText(arguments, "externalSource", 64);
        String externalSourceId = McpArgumentValidator.optionalText(arguments, "externalSourceId", 128);
        JsonNode imageInputs = arguments.get("images");
        return projectDocumentCommandService.createWithContentFactory(
                projectId,
                new CreateProjectDocumentRequest(parentDocumentId, title,
                        null,
                        externalSource, externalSourceId),
                userId,
                documentId -> documentImageContentService.convert(
                        markdown == null ? "" : markdown, parseDocumentImages(imageInputs), documentId, userId));
    }

    private Object updateDocument(JsonNode rawArguments, Long userId) {
        JsonNode arguments = McpArgumentValidator.object(rawArguments);
        McpArgumentValidator.fields(arguments, "documentId", "expectedVersion", "title", "content", "images");
        Long documentId = requiredLong(arguments, "documentId");
        Long expectedVersion = requiredLong(arguments, "expectedVersion");
        String title = McpArgumentValidator.requiredText(arguments, "title", 256);
        String markdown = McpArgumentValidator.requiredText(arguments, "content", 2_000_000);
        JsonNode imageInputs = arguments.get("images");
        return projectDocumentCommandService.updateWithContentFactory(
                documentId,
                new UpdateProjectDocumentRequest(expectedVersion, title, null),
                userId,
                id -> documentImageContentService.convert(markdown, parseDocumentImages(imageInputs), id, userId));
    }

    private List<McpDocumentImageInput> parseDocumentImages(JsonNode value) {
        if (value == null) {
            return List.of();
        }
        if (!value.isArray() || value.size() > MAX_DOCUMENT_IMAGES) {
            throw new McpInvalidParamsException("images 必须是最多 " + MAX_DOCUMENT_IMAGES + " 项的数组");
        }
        List<McpDocumentImageInput> images = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        int totalBytes = 0;
        long totalBase64Chars = 0;
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new McpInvalidParamsException("images 元素必须是对象");
            }
            String ref = McpArgumentValidator.requiredText(item, "ref", 64);
            if (!IMAGE_REF_PATTERN.matcher(ref).matches() || !refs.add(ref)) {
                throw new McpInvalidParamsException("images.ref 必须唯一且由字母、数字、下划线或短横线组成");
            }
            Long assetId = McpArgumentValidator.optionalLong(item, "assetId");
            if (assetId != null) {
                McpArgumentValidator.fields(item, "ref", "assetId");
                if (assetId <= 0) {
                    throw new McpInvalidParamsException("images.assetId 必须是正整数");
                }
                images.add(new McpDocumentImageInput(ref, null, null, null, assetId));
                continue;
            }

            McpArgumentValidator.fields(item, "ref", "fileName", "contentType", "dataBase64");
            String fileName = McpArgumentValidator.requiredText(item, "fileName", 256);
            String contentType = McpArgumentValidator.requiredText(item, "contentType", 128)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                throw new McpInvalidParamsException("images.contentType 必须是 image/*");
            }
            JsonNode base64Node = item.get("dataBase64");
            if (base64Node == null || !base64Node.isTextual()) {
                throw new McpInvalidParamsException("images.dataBase64 必须是字符串");
            }
            String base64 = base64Node.textValue();
            if (base64.length() > MAX_IMAGE_BASE64_CHARS) {
                throw new McpInvalidParamsException("单张 MCP 图片编码不能超过 8 MiB");
            }
            totalBase64Chars += base64.length();
            if (totalBase64Chars > MAX_TOTAL_IMAGE_BASE64_CHARS) {
                throw new McpInvalidParamsException("MCP 图片编码总量不能超过 16 MiB");
            }
            byte[] content;
            try {
                content = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new McpInvalidParamsException("images.dataBase64 必须是有效的 Base64");
            }
            if (content.length == 0 || content.length > MAX_IMAGE_BYTES) {
                throw new McpInvalidParamsException("单张 MCP 图片大小必须在 1 字节到 6 MiB 之间");
            }
            totalBytes += content.length;
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw new McpInvalidParamsException("MCP 图片总量不能超过 12 MiB");
            }
            images.add(new McpDocumentImageInput(ref, fileName, contentType, content, null));
        }
        return images;
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
        Map<String, ObjectNode> properties = new LinkedHashMap<>(Map.of(
                        "projectId", integerSchema("项目 ID"),
                        "parentDocumentId", integerSchema("父文档 ID"),
                        "title", stringSchema("文档标题", 1, 256),
                        "content", stringSchema("Markdown 正文，支持 GFM 表格", 0, 2_000_000),
                        "externalSource", stringSchema("外部来源类型", 1, 64),
                        "externalSourceId", stringSchema("外部来源文档 ID", 1, 128)));
        properties.put("images", documentImagesSchema());
        return tool("create_document", "创建文档",
                "创建 Markdown 文档并转换为 BlockNote JSON；GFM 表格会转为原生 BlockNote 表格块。正文每个不同图片 ref 都要在 images 提供对应附件，且不允许未使用项。例：content=\"前文 ![流程图](mcp-image:flow) 后文\"，images=[{ref:\"flow\",fileName:\"flow.png\",contentType:\"image/png\",dataBase64:\"<Base64>\"}]；也可用 {ref:\"flow\",assetId:31} 引用有权限访问的已有附件。最多 8 个图片引用，单图不超过 6 MiB、总量不超过 12 MiB；不接受远程图片 URL。",
                objectSchema(properties, "projectId", "title"),
                false, false);
    }

    private ObjectNode listDocumentsDefinition() {
        return tool("list_documents", "获取文档列表", "获取项目文档树；默认只返回未归档文档，不包含正文内容。",
                objectSchema(Map.of(
                        "projectId", integerSchema("项目 ID"),
                        "archived", booleanSchema("是否查询已归档文档")), "projectId"),
                true, true);
    }

    private ObjectNode getDocumentDefinition() {
        return tool("get_document", "获取文档内容", "按文档 ID 或精确标题获取文档标题、BlockNote JSON 正文、版本号和元数据。",
                getDocumentInputSchema(),
                true, true);
    }

    private ObjectNode getDocumentInputSchema() {
        ObjectNode schema = objectSchema(Map.of(
                "documentId", integerSchema("文档 ID"),
                "title", stringSchema("文档精确标题", 1, 256)));
        ArrayNode oneOf = schema.putArray("oneOf");
        oneOf.add(objectSchema(Map.of("documentId", integerSchema("文档 ID")), "documentId"));
        oneOf.add(objectSchema(Map.of("title", stringSchema("文档精确标题", 1, 256)), "title"));
        return schema;
    }

    private ObjectNode updateDocumentDefinition() {
        Map<String, ObjectNode> properties = new LinkedHashMap<>(Map.of(
                        "documentId", integerSchema("文档 ID"),
                        "expectedVersion", integerSchema("客户端已读版本号"),
                        "title", stringSchema("文档标题", 1, 256),
                        "content", stringSchema("Markdown 正文，支持 GFM 表格", 1, 2_000_000)));
        properties.put("images", documentImagesSchema());
        return tool("update_document", "更新文档",
                "按 expectedVersion 更新 Markdown 文档并转换为 BlockNote JSON；GFM 表格会转为原生 BlockNote 表格块。更新正文每个不同图片 ref 都要在 images 提供对应附件，且不允许未使用项。例：content=\"前文 ![流程图](mcp-image:flow) 后文\"，images=[{ref:\"flow\",fileName:\"flow.png\",contentType:\"image/png\",dataBase64:\"<Base64>\"}]；也可用 {ref:\"flow\",assetId:31} 引用有权限访问的已有附件。最多 8 个图片引用，单图不超过 6 MiB、总量不超过 12 MiB；不接受远程图片 URL。",
                objectSchema(properties, "documentId", "expectedVersion", "title", "content"), false, false);
    }

    private ObjectNode documentImagesSchema() {
        ObjectNode array = objectMapper.createObjectNode();
        array.put("type", "array");
        array.put("maxItems", MAX_DOCUMENT_IMAGES);
        array.put("description", "Markdown mcp-image:<ref> 图片引用对应的附件资源清单");
        array.set("items", documentImageInputSchema());
        return array;
    }

    private ObjectNode documentImageInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ArrayNode choices = schema.putArray("oneOf");
        choices.add(objectSchema(Map.of(
                "ref", stringSchema("Markdown 中 mcp-image:<ref> 使用的唯一标识", 1, 64),
                "fileName", stringSchema("上传文件名", 1, 256),
                "contentType", stringSchema("图片 MIME 类型，例如 image/png", 1, 128),
                "dataBase64", stringSchema("图片文件内容的 Base64；单图最多 8 MiB 编码", 1, MAX_IMAGE_BASE64_CHARS)),
                "ref", "fileName", "contentType", "dataBase64"));
        choices.add(objectSchema(Map.of(
                "ref", stringSchema("Markdown 中 mcp-image:<ref> 使用的唯一标识", 1, 64),
                "assetId", integerSchema("已上传的文档图片附件 ID")), "ref", "assetId"));
        return schema;
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
        tool.put("description", TOOL_USAGE_PREFIX + description);
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
