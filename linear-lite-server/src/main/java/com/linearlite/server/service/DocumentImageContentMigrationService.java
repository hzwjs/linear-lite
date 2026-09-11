package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把存量正文与历史修订里的 image 块（下载 URL）迁移为 documentImage 块（assetId）。
 *
 * <p>不做双读双写：迁移一次性完成后前端只认 assetId。迁移前全量校验，任一条目无法解析即中止且不写库，
 * 保证失败时数据保持原样。
 */
@Service
public class DocumentImageContentMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImageContentMigrationService.class);
    private static final Pattern ATTACHMENT_URL =
            Pattern.compile("^/api/project-documents/(\\d+)/attachments/(\\d+)/download$");

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ProjectDocumentAttachmentMapper attachmentMapper;
    private final ObjectMapper objectMapper;

    public DocumentImageContentMigrationService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            ProjectDocumentAttachmentMapper attachmentMapper,
            ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.attachmentMapper = attachmentMapper;
        this.objectMapper = objectMapper;
    }

    public record MigrationReport(
            int documentsScanned,
            int documentsMigrated,
            int revisionsScanned,
            int revisionsMigrated,
            List<String> errors) {
        public boolean aborted() {
            return !errors.isEmpty();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public MigrationReport migrate() {
        List<String> errors = new ArrayList<>();
        List<Runnable> pendingUpdates = new ArrayList<>();
        int documentsMigrated = 0;
        int revisionsMigrated = 0;

        List<ProjectDocument> documents = documentMapper.selectList(null);
        for (ProjectDocument document : documents) {
            String migrated = migrateContent(
                    document.getContentJson(), document.getId(), "document:" + document.getId(), errors);
            if (migrated != null) {
                documentsMigrated++;
                Long documentId = document.getId();
                pendingUpdates.add(() -> documentMapper.update(null,
                        new UpdateWrapper<ProjectDocument>().eq("id", documentId).set("content_json", migrated)));
            }
        }

        List<ProjectDocumentRevision> revisions = revisionMapper.selectList(null);
        for (ProjectDocumentRevision revision : revisions) {
            String migrated = migrateContent(
                    revision.getContentJson(), revision.getDocumentId(), "revision:" + revision.getId(), errors);
            if (migrated != null) {
                revisionsMigrated++;
                Long revisionId = revision.getId();
                pendingUpdates.add(() -> revisionMapper.update(null,
                        new UpdateWrapper<ProjectDocumentRevision>()
                                .eq("id", revisionId).set("content_json", migrated)));
            }
        }

        if (!errors.isEmpty()) {
            log.error("文档图片迁移中止：发现 {} 条无法解析的数据，未写入任何变更", errors.size());
            return new MigrationReport(documents.size(), 0, revisions.size(), 0, errors);
        }
        for (Runnable update : pendingUpdates) {
            update.run();
        }
        log.info("文档图片迁移完成：文档 {}/{}，修订 {}/{}",
                documentsMigrated, documents.size(), revisionsMigrated, revisions.size());
        return new MigrationReport(
                documents.size(), documentsMigrated, revisions.size(), revisionsMigrated, List.of());
    }

    /**
     * @return 迁移后的正文 JSON；无需变更时返回 {@code null}
     */
    private String migrateContent(String contentJson, Long documentId, String location, List<String> errors) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(contentJson);
        } catch (Exception e) {
            errors.add(location + " 正文不是有效 JSON: " + e.getMessage());
            return null;
        }
        if (root == null || !root.isArray()) {
            return null;
        }
        boolean changed = false;
        for (JsonNode block : root) {
            changed |= migrateBlock(block, documentId, location, errors);
        }
        if (!changed) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            errors.add(location + " 序列化迁移结果失败: " + e.getMessage());
            return null;
        }
    }

    private boolean migrateBlock(JsonNode block, Long documentId, String location, List<String> errors) {
        if (block == null || !block.isObject()) {
            return false;
        }
        boolean changed = false;
        JsonNode children = block.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                changed |= migrateBlock(child, documentId, location, errors);
            }
        }
        if (!"image".equals(block.path("type").asText())) {
            return changed;
        }
        JsonNode props = block.get("props");
        String url = props == null ? null : props.path("url").asText(null);
        if (url == null) {
            return changed;
        }
        Matcher matcher = ATTACHMENT_URL.matcher(url);
        if (!matcher.matches()) {
            // 非文档附件地址（外部图片等）不参与迁移，保持原样。
            return changed;
        }
        long urlDocumentId = Long.parseLong(matcher.group(1));
        long attachmentId = Long.parseLong(matcher.group(2));
        if (!documentId.equals(urlDocumentId)) {
            errors.add(location + " 图片地址指向其它文档: " + url);
            return changed;
        }
        ProjectDocumentAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !documentId.equals(attachment.getDocumentId())) {
            errors.add(location + " 图片附件不存在或不属于该文档: " + attachmentId);
            return changed;
        }
        if (attachment.getContentType() == null
                || !attachment.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            errors.add(location + " 附件不是图片: " + attachmentId);
            return changed;
        }
        String caption = props.path("caption").asText("");
        ObjectNode object = (ObjectNode) block;
        object.put("type", "documentImage");
        ObjectNode newProps = objectMapper.createObjectNode();
        newProps.put("imageAssetId", attachmentId);
        newProps.put("caption", caption);
        object.set("props", newProps);
        return true;
    }
}
