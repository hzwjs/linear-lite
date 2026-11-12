package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.dto.CollaborationAttemptCommand;
import com.linearlite.server.dto.CollaborationBatchRequest;
import com.linearlite.server.dto.CollaborationContextRef;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 上下文引用归属执行检查点；文档正文由文档服务提供。 */
@Service
public class CollaborationContextService {
    private final CollaborationContextReadAuthorizationService reads;
    private final TaskExecutionAccessService access;
    private final TaskExecutionOperationService operations;
    private final TaskExecutionAttemptMapper attempts;
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentQueryService documents;
    private final ObjectMapper json;

    public CollaborationContextService(CollaborationContextReadAuthorizationService reads,
            TaskExecutionAccessService access, TaskExecutionOperationService operations,
            TaskExecutionAttemptMapper attempts, ProjectDocumentMapper documentMapper,
            ProjectDocumentQueryService documents, ObjectMapper json) {
        this.reads = reads; this.access = access; this.operations = operations; this.attempts = attempts;
        this.documentMapper = documentMapper; this.documents = documents; this.json = json;
    }

    public List<ProjectDocumentTreeNode> listDocuments(Long userId, CollaborationBatchRequest batch,
                                                        Long taskId, CollaborationAttemptCommand command) {
        Long projectId = reads.authorize(userId, batch, taskId, command);
        return documents.listTree(projectId, userId, false);
    }

    public ProjectDocumentResponse readDocument(Long userId, CollaborationBatchRequest batch, Long taskId,
                                                 CollaborationAttemptCommand command, Long documentId) {
        Long projectId = reads.authorize(userId, batch, taskId, command);
        ProjectDocument document = documents.requireDocument(documentId, userId);
        if (!Objects.equals(projectId, document.getProjectId()) || document.getArchivedAt() != null)
            throw new ForbiddenOperationException("文档不属于当前项目或不可用");
        // flush 可能调用 Java 提交新版本，不能持项目锁等待。
        ProjectDocumentResponse result = documents.getDocument(documentId, userId);
        if (!Objects.equals(projectId, result.projectId()) || result.archivedAt() != null)
            throw new ForbiddenOperationException("文档不属于当前项目或不可用");
        return result;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public JsonNode adopt(Long userId, CollaborationBatchRequest batch, Long taskId,
                          CollaborationAttemptCommand command, List<CollaborationContextRef> contextRefs) {
        validateRefs(contextRefs);
        var owned = access.requireOwnedAttempt(userId, batch, taskId, command);
        ObjectNode request = json.createObjectNode().put("batchId", owned.context().batchId())
                .put("bindingId", owned.context().bindingId()).put("taskId", taskId)
                .put("attemptId", command.attemptId()).put("inputRevision", command.inputRevision())
                .put("hostInstanceId", command.hostInstanceId()).put("agentInstanceId", command.agentInstanceId());
        request.set("contextRefs", json.valueToTree(contextRefs));
        CollaborationCredentialPolicy.requireSafe(request, batch.batchToken(), command.claimToken());
        CollaborationCredentialPolicy.requireSafe(command.operationId(), batch.batchToken(), command.claimToken());
        JsonNode replay = operations.findReplay(owned.context().projectId(), userId, command.operationId(), "context_adopt", request);
        if (replay != null) return replay;
        var check = access.checkWritable(owned, command);
        ObjectNode response = json.createObjectNode().put("accepted", check.accepted());
        if (!check.accepted()) {
            response.put("reason", check.reason());
        } else {
            for (CollaborationContextRef ref : contextRefs) {
                if ("DOCUMENT".equals(ref.kind())) requireCurrentDocument(owned.context().projectId(), ref);
            }
            Map<String, CollaborationContextRef> existing = latestRefs(owned.attempt());
            List<CollaborationContextRef> changes = contextRefs.stream()
                    .filter(ref -> !Objects.equals(existing.get(key(ref)), ref)).toList();
            if (!changes.isEmpty()) {
                ObjectNode fact = json.createObjectNode().put("operationId", command.operationId())
                        .put("agentInstanceId", command.agentInstanceId()).put("occurredAt", LocalDateTime.now().toString());
                fact.set("contextRefs", json.valueToTree(changes));
                append(owned.attempt(), fact);
            }
            response.set("contextRefs", json.valueToTree(contextRefs));
        }
        operations.save(owned.context().projectId(), userId, command.operationId(), "context_adopt",
                command.attemptId(), request, response);
        return response;
    }

    /** 文档事务持项目锁；只为消费旧版本的尝试追加复核事实，不停止执行。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDocumentChanged(CollaborationDocumentChangedEvent event) {
        for (TaskExecutionAttempt attempt : attempts.selectByProjectForUpdate(event.projectId())) {
            List<ObjectNode> changed = new ArrayList<>();
            for (CollaborationContextRef ref : latestRefs(attempt).values()) {
                if (!"DOCUMENT".equals(ref.kind())) continue;
                event.changes().stream().filter(change -> Objects.equals(change.documentId(), ref.documentId())
                        && (!change.available() || !Objects.equals(change.version(), ref.documentVersion())))
                        .forEach(change -> changed.add(json.createObjectNode().put("documentId", ref.documentId())
                                .put("adoptedVersion", ref.documentVersion()).put("available", change.available())
                                .put("currentVersion", change.version())));
            }
            if (!changed.isEmpty()) {
                ObjectNode fact = json.createObjectNode().put("kind", "CONTEXT_REVIEW_REQUIRED")
                        .put("occurredAt", LocalDateTime.now().toString());
                fact.set("reviewRefs", json.valueToTree(changed));
                append(attempt, fact);
            }
        }
    }

    public List<ContextStatus> project(TaskExecutionAttempt attempt, Long projectId, Map<String, String> hostFileVersions) {
        List<ContextStatus> result = new ArrayList<>();
        for (CollaborationContextRef ref : latestRefs(attempt).values()) {
            String state;
            String documentTitle = null;
            if ("DOCUMENT".equals(ref.kind())) {
                ProjectDocument document = documentMapper.selectById(ref.documentId());
                boolean belongsToProject = document != null && Objects.equals(document.getProjectId(), projectId);
                if (belongsToProject) documentTitle = document.getTitle();
                state = belongsToProject && document.getArchivedAt() == null && Objects.equals(document.getVersion(), ref.documentVersion())
                        ? "CURRENT" : "REVIEW_REQUIRED";
            } else {
                String current = hostFileVersions.get(ref.projectPath());
                state = current == null ? "PENDING_CONFIRMATION"
                        : current.equals(ref.contentVersion()) ? "CURRENT" : "REVIEW_REQUIRED";
            }
            result.add(new ContextStatus(ref, documentTitle, state));
        }
        return List.copyOf(result);
    }

    /** 交付使用本次尝试已采用的最后版本。 */
    public static Map<String, CollaborationContextRef> latestRefs(TaskExecutionAttempt attempt) {
        Map<String, CollaborationContextRef> result = new LinkedHashMap<>();
        JsonNode checkpoints = attempt.getCheckpoints();
        if (checkpoints == null || !checkpoints.isArray()) return result;
        for (JsonNode point : checkpoints) {
            JsonNode refs = point.get("contextRefs");
            if (refs == null || !refs.isArray()) continue;
            for (JsonNode ref : refs) {
                CollaborationContextRef value = new CollaborationContextRef(ref.path("kind").asText(),
                        ref.path("documentId").isIntegralNumber() ? ref.path("documentId").longValue() : null,
                        ref.path("documentVersion").isIntegralNumber() ? ref.path("documentVersion").longValue() : null,
                        ref.path("projectPath").isTextual() ? ref.path("projectPath").textValue() : null,
                        ref.path("contentVersion").isTextual() ? ref.path("contentVersion").textValue() : null);
                validateRefs(List.of(value));
                result.put(key(value), value);
            }
        }
        return result;
    }

    public static void requireAdopted(TaskExecutionAttempt attempt, List<CollaborationContextRef> refs) {
        if (refs == null) throw new IllegalArgumentException("上下文引用不能为空");
        if (refs.isEmpty()) return;
        validateRefs(refs);
        Map<String, CollaborationContextRef> adopted = latestRefs(attempt);
        for (CollaborationContextRef ref : refs) {
            if (!Objects.equals(adopted.get(key(ref)), ref))
                throw new IllegalArgumentException("上下文引用未由当前执行采用");
        }
    }

    public static void requireAllAdopted(TaskExecutionAttempt attempt, List<CollaborationContextRef> refs) {
        requireAdopted(attempt, refs);
        if (refs.size() != latestRefs(attempt).size())
            throw new IllegalArgumentException("交付证据缺少本次执行采用的上下文");
    }

    private static String key(CollaborationContextRef ref) {
        return "DOCUMENT".equals(ref.kind()) ? "DOCUMENT:" + ref.documentId() : "PROJECT_FILE:" + ref.projectPath();
    }

    private static void validateRefs(List<CollaborationContextRef> refs) {
        if (refs == null || refs.isEmpty()) throw new IllegalArgumentException("上下文引用不能为空");
        var keys = new java.util.HashSet<String>();
        for (CollaborationContextRef ref : refs) {
            if (ref == null) throw new IllegalArgumentException("上下文引用无效");
            if ("DOCUMENT".equals(ref.kind())) {
                if (ref.documentId() == null || ref.documentId() <= 0 || ref.documentVersion() == null
                        || ref.documentVersion() <= 0 || ref.projectPath() != null || ref.contentVersion() != null)
                    throw new IllegalArgumentException("项目文档引用无效");
            } else if ("PROJECT_FILE".equals(ref.kind())) {
                if (ref.documentId() != null || ref.documentVersion() != null || ref.projectPath() == null
                        || ref.projectPath().endsWith("/") || ref.contentVersion() == null
                        || !ref.contentVersion().matches("sha256:[0-9a-f]{64}"))
                    throw new IllegalArgumentException("项目文件引用无效");
                ProjectArtifactPathService.validate(List.of(ref.projectPath()));
            } else throw new IllegalArgumentException("上下文来源无效");
            if (!keys.add(key(ref))) throw new IllegalArgumentException("上下文来源重复");
        }
    }

    private void requireCurrentDocument(Long projectId, CollaborationContextRef ref) {
        ProjectDocument document = documentMapper.selectById(ref.documentId());
        if (document == null || !Objects.equals(projectId, document.getProjectId()) || document.getArchivedAt() != null)
            throw new ForbiddenOperationException("文档不属于当前项目或不可用");
        if (!Objects.equals(document.getVersion(), ref.documentVersion()))
            throw new ConflictOperationException("文档版本已变化，请读取当前内容");
    }

    private void append(TaskExecutionAttempt attempt, ObjectNode fact) {
        JsonNode previous = attempt.getCheckpoints();
        if (previous == null || !previous.isArray()) throw new IllegalStateException("检查点数据无效");
        try {
            if (attempts.appendCheckpoint(attempt.getId(), previous.size(), json.writeValueAsString(fact)) != 1)
                throw new IllegalStateException("检查点追加失败");
        } catch (JsonProcessingException e) { throw new IllegalStateException("检查点序列化失败"); }
    }

    public record ContextStatus(CollaborationContextRef ref, String documentTitle, String status) { }
}
