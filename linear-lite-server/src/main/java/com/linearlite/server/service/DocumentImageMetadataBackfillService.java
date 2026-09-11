package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * 为存量图片附件回填尺寸与缩略图。
 *
 * <p>只处理 content_type 为 image/* 的附件；ImageIO 无法解码的格式跳过（渲染回退原图）。
 * 单张失败只记录并继续，不阻断整体回填。
 */
@Service
public class DocumentImageMetadataBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImageMetadataBackfillService.class);

    private final ProjectDocumentAttachmentMapper attachmentMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentImageProcessor imageProcessor;

    public DocumentImageMetadataBackfillService(
            ProjectDocumentAttachmentMapper attachmentMapper,
            ObjectStorageService objectStorageService,
            DocumentImageProcessor imageProcessor) {
        this.attachmentMapper = attachmentMapper;
        this.objectStorageService = objectStorageService;
        this.imageProcessor = imageProcessor;
    }

    /** @return 回填成功的附件数量 */
    @Transactional(rollbackFor = Exception.class)
    public int backfill() {
        List<ProjectDocumentAttachment> images = attachmentMapper.selectList(
                new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .likeRight(ProjectDocumentAttachment::getContentType, "image/"));
        int updated = 0;
        for (ProjectDocumentAttachment attachment : images) {
            if (attachment.getWidth() != null
                    && attachment.getHeight() != null
                    && attachment.getThumbnailObjectKey() != null) {
                continue;
            }
            try (InputStream input = objectStorageService.openObjectStreamByKey(attachment.getObjectKey())) {
                DocumentImageProcessor.ImageMetadata metadata = imageProcessor.analyze(input.readAllBytes());
                if (metadata == null) {
                    log.warn("跳过无法解码的存量图片: attachmentId={}", attachment.getId());
                    continue;
                }
                String thumbnailKey = null;
                Long thumbnailSize = null;
                String thumbnailContentType = null;
                if (metadata.hasThumbnail()) {
                    thumbnailKey = objectStorageService.uploadProjectDocumentThumbnail(
                            metadata.thumbnail(), metadata.thumbnailContentType(),
                            attachment.getProjectId(), attachment.getDocumentId());
                    thumbnailSize = (long) metadata.thumbnail().length;
                    thumbnailContentType = metadata.thumbnailContentType();
                }
                attachmentMapper.update(null, new UpdateWrapper<ProjectDocumentAttachment>()
                        .eq("id", attachment.getId())
                        .set("width", metadata.width())
                        .set("height", metadata.height())
                        .set("thumbnail_object_key", thumbnailKey)
                        .set("thumbnail_file_size", thumbnailSize)
                        .set("thumbnail_content_type", thumbnailContentType));
                updated++;
            } catch (Exception e) {
                log.warn("文档图片元数据回填失败: attachmentId={}", attachment.getId(), e);
            }
        }
        if (updated > 0) {
            log.info("文档图片元数据回填完成：更新 {} 张", updated);
        }
        return updated;
    }
}
