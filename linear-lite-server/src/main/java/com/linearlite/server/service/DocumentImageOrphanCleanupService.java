package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import com.linearlite.server.time.BeijingTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回收没有任何当前正文或历史修订引用的文档图片附件。
 *
 * <p>历史修订保留引用，因此本任务主要清理“上传后从未写入正文”的草稿附件；对象与元数据同时删除。
 */
@Service
public class DocumentImageOrphanCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImageOrphanCleanupService.class);
    private static final Pattern ATTACHMENT_URL =
            Pattern.compile("^/api/project-documents/(\\d+)/attachments/(\\d+)/download$");

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ProjectDocumentAttachmentMapper attachmentMapper;
    private final ObjectStorageService objectStorageService;
    private final ObjectMapper objectMapper;

    public DocumentImageOrphanCleanupService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            ProjectDocumentAttachmentMapper attachmentMapper,
            ObjectStorageService objectStorageService,
            ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.attachmentMapper = attachmentMapper;
        this.objectStorageService = objectStorageService;
        this.objectMapper = objectMapper;
    }

    /** @return 删除的附件数量 */
    public int cleanup(Duration minAge) {
        LocalDateTime threshold = BeijingTime.now().minus(minAge);
        List<ProjectDocumentAttachment> candidates = attachmentMapper.selectList(
                new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .likeRight(ProjectDocumentAttachment::getContentType, "image/")
                        .lt(ProjectDocumentAttachment::getCreatedAt, threshold));
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, Optional<Set<Long>>> referencedByProject = new HashMap<>();
        int deleted = 0;
        for (ProjectDocumentAttachment attachment : candidates) {
            Optional<Set<Long>> referenced = referencedByProject.computeIfAbsent(
                    attachment.getProjectId(), this::collectReferencedAssetIds);
            if (referenced.isEmpty() || referenced.get().contains(attachment.getId())) {
                continue;
            }
            objectStorageService.deleteObjectByKey(attachment.getObjectKey());
            if (attachment.getThumbnailObjectKey() != null) {
                objectStorageService.deleteObjectByKey(attachment.getThumbnailObjectKey());
            }
            attachmentMapper.deleteById(attachment.getId());
            deleted++;
        }
        if (deleted > 0) {
            log.info("文档图片回收完成：清理未引用附件 {} 个", deleted);
        }
        return deleted;
    }

    private Optional<Set<Long>> collectReferencedAssetIds(Long projectId) {
        Set<Long> assetIds = new HashSet<>();
        List<ProjectDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<ProjectDocument>()
                        .eq(ProjectDocument::getProjectId, projectId)
                        .select(ProjectDocument::getId, ProjectDocument::getContentJson));
        List<Long> documentIds = documents.stream().map(ProjectDocument::getId).toList();
        for (ProjectDocument document : documents) {
            if (!collectAssetIds(document.getContentJson(), assetIds)) {
                log.error("跳过文档图片回收：项目正文解析失败，projectId={}, documentId={}",
                        projectId, document.getId());
                return Optional.empty();
            }
        }
        if (documentIds.isEmpty()) {
            return Optional.of(assetIds);
        }
        List<ProjectDocumentRevision> revisions = revisionMapper.selectList(
                new LambdaQueryWrapper<ProjectDocumentRevision>()
                        .in(ProjectDocumentRevision::getDocumentId, documentIds)
                        .select(ProjectDocumentRevision::getId, ProjectDocumentRevision::getContentJson));
        for (ProjectDocumentRevision revision : revisions) {
            if (!collectAssetIds(revision.getContentJson(), assetIds)) {
                log.error("跳过文档图片回收：历史修订正文解析失败，projectId={}, revisionId={}",
                        projectId, revision.getId());
                return Optional.empty();
            }
        }
        return Optional.of(assetIds);
    }

    private boolean collectAssetIds(String contentJson, Set<Long> assetIds) {
        if (contentJson == null || contentJson.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            if (root == null || !root.isArray()) {
                return false;
            }
            for (JsonNode block : root) {
                collectAssetIds(block, assetIds);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void collectAssetIds(JsonNode block, Set<Long> assetIds) {
        if (block == null || !block.isObject()) {
            return;
        }
        JsonNode children = block.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectAssetIds(child, assetIds);
            }
        }
        String type = block.path("type").asText();
        if ("documentImage".equals(type)) {
            long assetId = block.path("props").path("imageAssetId").asLong(0);
            if (assetId > 0) {
                assetIds.add(assetId);
            }
            return;
        }
        if ("image".equals(type)) {
            String url = block.path("props").path("url").asText(null);
            if (url == null) {
                return;
            }
            Matcher matcher = ATTACHMENT_URL.matcher(url);
            if (matcher.matches()) {
                assetIds.add(Long.parseLong(matcher.group(2)));
            }
        }
    }
}
