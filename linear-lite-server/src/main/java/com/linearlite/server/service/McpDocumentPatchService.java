package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.McpDocumentPatchOperation;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.McpDocumentPatchOperationMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

@Service
public class McpDocumentPatchService {
    private final ProjectDocumentMapper documentMapper;
    private final McpDocumentPatchOperationMapper operationMapper;
    private final ProjectAccessGuard accessGuard;
    private final DocumentCollaborationService collaborationService;
    private final CollaborationNodeClient nodeClient;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ObjectMapper objectMapper;

    public McpDocumentPatchService(
            ProjectDocumentMapper documentMapper,
            McpDocumentPatchOperationMapper operationMapper,
            ProjectAccessGuard accessGuard,
            DocumentCollaborationService collaborationService,
            CollaborationNodeClient nodeClient,
            ObjectMapper objectMapper,
            ProjectDocumentRevisionMapper revisionMapper) {
        this.documentMapper = documentMapper;
        this.operationMapper = operationMapper;
        this.accessGuard = accessGuard;
        this.collaborationService = collaborationService;
        this.nodeClient = nodeClient;
        this.objectMapper = objectMapper;
        this.revisionMapper = revisionMapper;
    }

    public JsonNode patch(
            Long documentId, Long expectedVersion, String operationId, String requestSha256,
            String title, Long callerId, BiFunction<String, Long, String> contentFactory) {
        accessible(documentId, callerId);
        ProjectDocument current = documentMapper.selectById(documentId);
        if (current == null || current.getArchivedAt() != null) {
            throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        }
        accessGuard.requireMember(current.getProjectId(), callerId);

        McpDocumentPatchOperation previous = operationMapper.selectForUpdate(documentId, callerId, operationId);
        if (previous != null) {
            if (!requestSha256.equals(previous.getRequestSha256())) {
                throw new ConflictOperationException("operationId 已用于不同的 patch_document 请求");
            }
            return readJson(previous.getResponseJson());
        }
        if (!"BLOCKNOTE".equals(current.getContentFormat())) {
            throw new IllegalArgumentException("patch_document 仅支持 BLOCKNOTE 文档");
        }
        if (!Objects.equals(current.getVersion(), expectedVersion)) {
            throw new DocumentVersionConflictException(current.getVersion());
        }

        CollaborationNodeClient.SnapshotResponse snapshot = collaborationService.getSnapshot(documentId, true);
        if (!Objects.equals(snapshot.version(), expectedVersion)) {
            throw new DocumentVersionConflictException(snapshot.version());
        }
        String nextTitle = title == null ? current.getTitle() : title;
        String nextContent = requireBlockNoteJson(contentFactory.apply(snapshot.content(), documentId));
        collaborationService.validateReplacement(documentId, "BLOCKNOTE", nextContent, callerId);
        boolean changed = !Objects.equals(current.getTitle(), nextTitle)
                || !sameJson(snapshot.content(), nextContent);
        Long revisionId = null;
        Long resultingVersion = current.getVersion();
        if (changed) {
            CollaborationNodeClient.CommandResponse result = nodeClient.command(documentId,
                    new CollaborationNodeClient.CommandRequest(operationId, callerId, snapshot.epoch(),
                            expectedVersion, "replace", "BLOCKNOTE", nextContent, nextTitle));
            if (result == null || result.version() == null) {
                throw new IllegalStateException("协作服务未返回 patch_document 提交版本");
            }
            resultingVersion = result.version();
            ProjectDocumentRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                    .eq(ProjectDocumentRevision::getDocumentId, documentId)
                    .eq(ProjectDocumentRevision::getVersion, resultingVersion));
            revisionId = revision == null ? null : revision.getId();
        } else {
            ProjectDocumentRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                    .eq(ProjectDocumentRevision::getDocumentId, documentId)
                    .eq(ProjectDocumentRevision::getVersion, current.getVersion()));
            revisionId = revision == null ? null : revision.getId();
        }

        Map<String, Object> response = Map.of(
                "documentId", documentId,
                "version", resultingVersion,
                "changed", changed,
                "revisionId", revisionId == null ? objectMapper.nullNode() : revisionId);
        JsonNode responseJson = objectMapper.valueToTree(response);
        McpDocumentPatchOperation record = new McpDocumentPatchOperation();
        record.setDocumentId(documentId);
        record.setCallerId(callerId);
        record.setOperationId(operationId);
        record.setRequestSha256(requestSha256);
        try {
            record.setResponseJson(objectMapper.writeValueAsString(responseJson));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存 patch_document 幂等结果", e);
        }
        operationMapper.insert(record);
        return responseJson;
    }

    private ProjectDocument accessible(Long documentId, Long callerId) {
        ProjectDocument document = documentMapper.selectById(documentId);
        if (document == null) throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        accessGuard.requireMember(document.getProjectId(), callerId);
        if (document.getArchivedAt() != null) throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        return document;
    }

    private String requireBlockNoteJson(String content) {
        if (content == null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("BlockNote 正文不能为空且不能超过 5 MiB");
        }
        try {
            JsonNode tree = objectMapper.readTree(content);
            if (tree == null || !tree.isArray()) throw new IllegalArgumentException("正文必须是 BlockNote 数组");
            return content;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("正文必须是有效的 BlockNote JSON 数组");
        }
    }

    private boolean sameJson(String first, String second) {
        try {
            return objectMapper.readTree(first).equals(objectMapper.readTree(second));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("正文必须是有效的 BlockNote JSON 数组");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("patch_document 幂等结果无法解析", e);
        }
    }
}
