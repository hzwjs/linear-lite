package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.AttachmentDownload;
import com.linearlite.server.dto.DocumentAssetDescriptor;
import com.linearlite.server.dto.DocumentImageAsset;
import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.time.BeijingTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

@Service
public class ProjectDocumentAttachmentService {

    /** 正文图片节点只保存 assetId，其余元数据由文档图片资源清单解析。 */
    public static final String THUMBNAIL_VARIANT = "thumbnail";
    public static final String ORIGINAL_VARIANT = "original";

    private static final String CLONE_SOURCE_PREFIX = "copied-from:";

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentAttachmentMapper attachmentMapper;
    private final ProjectAccessGuard accessGuard;
    private final ObjectStorageService objectStorageService;
    private final DocumentImageProcessor imageProcessor;
    private final long maxBytes;

    public ProjectDocumentAttachmentService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentAttachmentMapper attachmentMapper,
            ProjectAccessGuard accessGuard,
            ObjectStorageService objectStorageService,
            DocumentImageProcessor imageProcessor,
            @Value("${app.storage.document-attachment-max-bytes:2147483648}") long maxBytes) {
        this.documentMapper = documentMapper;
        this.attachmentMapper = attachmentMapper;
        this.accessGuard = accessGuard;
        this.objectStorageService = objectStorageService;
        this.imageProcessor = imageProcessor;
        this.maxBytes = maxBytes;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentAttachmentResponse upload(
            Long documentId, MultipartFile file, String sourceId, Long userId) {
        ProjectDocument document = requireDocument(documentId, userId);
        requireValidFile(file);
        String normalizedSourceId = normalizeSourceId(sourceId);
        String fileName = normalizeFileName(file.getOriginalFilename());
        String providedType = file.getContentType() == null
                ? ""
                : file.getContentType().trim().toLowerCase(Locale.ROOT);
        DocumentImageProcessor.ImageMetadata image = null;
        String contentType;
        if (providedType.isBlank() || "application/octet-stream".equals(providedType)) {
            // 浏览器未给出 MIME 时，以可解码位图作为图片来源的唯一判据。
            image = analyzeImage(file);
            contentType = image != null && image.sourceContentType() != null
                    ? image.sourceContentType()
                    : "application/octet-stream";
        } else {
            contentType = providedType;
            if (isImageContentType(contentType)) {
                image = analyzeImage(file);
            }
        }
        String sha256 = sha256(file);

        // 与文档创建/移动共用项目级写锁，保证相同 sourceId 的重跑不会并发上传两份对象。
        documentMapper.lockProjectDocumentMutations(document.getProjectId());
        if (normalizedSourceId != null) {
            ProjectDocumentAttachment existing = findBySourceId(documentId, normalizedSourceId);
            if (existing != null) {
                if (!sha256.equals(existing.getSha256()) || file.getSize() != existing.getFileSize()) {
                    throw new IllegalArgumentException("同一来源附件的内容已变化: " + normalizedSourceId);
                }
                return toResponse(existing);
            }
        }

        ImageUploadResponse uploaded;
        try (InputStream uploadStream = file.getInputStream()) {
            uploaded = objectStorageService.uploadProjectDocumentAttachment(
                    uploadStream, file.getSize(), fileName, contentType,
                    document.getProjectId(), documentId, maxBytes);
        } catch (IOException e) {
            throw new IllegalStateException("读取文档附件失败", e);
        }
        String thumbnailKey = null;
        try {
            if (image != null && image.hasThumbnail()) {
                thumbnailKey = objectStorageService.uploadProjectDocumentThumbnail(
                        image.thumbnail(), image.thumbnailContentType(), document.getProjectId(), documentId);
            }
            ProjectDocumentAttachment attachment = new ProjectDocumentAttachment();
            attachment.setProjectId(document.getProjectId());
            attachment.setDocumentId(documentId);
            attachment.setSourceId(normalizedSourceId);
            attachment.setObjectKey(uploaded.getKey());
            attachment.setFileName(fileName);
            attachment.setFileSize(file.getSize());
            attachment.setContentType(contentType);
            attachment.setSha256(sha256);
            if (image != null) {
                attachment.setWidth(image.width());
                attachment.setHeight(image.height());
                if (image.hasThumbnail()) {
                    attachment.setThumbnailObjectKey(thumbnailKey);
                    attachment.setThumbnailFileSize((long) image.thumbnail().length);
                    attachment.setThumbnailContentType(image.thumbnailContentType());
                }
            }
            // 附件时间由应用明确写入北京时间，不依赖数据库服务器或连接会话的默认时区。
            attachment.setCreatedAt(BeijingTime.now());
            attachmentMapper.insert(attachment);
            return toResponse(requireAttachment(documentId, attachment.getId()));
        } catch (RuntimeException | Error persistenceFailure) {
            // R2 不受数据库事务管理；元数据事务未成功返回时必须删除刚上传的对象。
            deleteObjectQuietly(uploaded.getKey(), persistenceFailure);
            if (thumbnailKey != null) {
                deleteObjectQuietly(thumbnailKey, persistenceFailure);
            }
            throw persistenceFailure;
        }
    }

    public List<ProjectDocumentAttachmentResponse> list(Long documentId, Long userId) {
        requireDocument(documentId, userId);
        return listAttachments(documentId).stream().map(this::toResponse).toList();
    }

    /**
     * 文档正文图片身份清单。调用方已校验成员关系，此处不再重复鉴权。
     */
    public List<DocumentImageAsset> listImageAssets(Long documentId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .eq(ProjectDocumentAttachment::getDocumentId, documentId)
                        .likeRight(ProjectDocumentAttachment::getContentType, "image/")
                        .orderByAsc(ProjectDocumentAttachment::getCreatedAt)
                        .orderByAsc(ProjectDocumentAttachment::getId))
                .stream()
                .map(this::toImageAsset)
                .toList();
    }

    public AttachmentDownload download(Long documentId, Long attachmentId, Long userId) {
        requireDocument(documentId, userId);
        ProjectDocumentAttachment attachment = requireAttachment(documentId, attachmentId);
        if (attachment.getFileSize() == null || attachment.getFileSize() > maxBytes) {
            throw new IllegalArgumentException("文档附件大小信息无效，拒绝下载");
        }
        return new AttachmentDownload(
                objectStorageService.openObjectStreamByKey(attachment.getObjectKey()),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getFileSize());
    }

    /**
     * 解析正文图片资源地址：先校验成员关系与附件归属，再按内容哈希定位不可变对象。
     */
    public DocumentAssetDescriptor resolveAsset(
            Long documentId, Long assetId, String contentHash, String variant, Long userId) {
        requireDocument(documentId, userId);
        ProjectDocumentAttachment attachment = requireAttachment(documentId, assetId);
        if (contentHash == null || !contentHash.equals(attachment.getSha256())) {
            throw new ResourceNotFoundException("文档图片资源不存在: " + assetId);
        }
        String objectKey;
        String contentType;
        Long contentLength;
        if (THUMBNAIL_VARIANT.equals(variant)) {
            if (attachment.getThumbnailObjectKey() == null) {
                throw new ResourceNotFoundException("文档图片缩略图不存在: " + assetId);
            }
            objectKey = attachment.getThumbnailObjectKey();
            contentType = attachment.getThumbnailContentType();
            contentLength = attachment.getThumbnailFileSize();
        } else if (ORIGINAL_VARIANT.equals(variant)) {
            objectKey = attachment.getObjectKey();
            contentType = attachment.getContentType();
            contentLength = attachment.getFileSize();
        } else {
            throw new IllegalArgumentException("不支持的图片资源变体: " + variant);
        }
        return new DocumentAssetDescriptor(
                objectKey,
                contentType,
                contentLength == null ? -1L : contentLength,
                THUMBNAIL_VARIANT.equals(variant)
                        ? buildThumbnailEtag(attachment.getThumbnailObjectKey())
                        : buildAssetEtag(attachment.getSha256()));
    }

    public InputStream openAssetStream(String objectKey) {
        return objectStorageService.openObjectStreamByKey(objectKey);
    }

    public void delete(Long documentId, Long attachmentId, Long userId) {
        requireDocument(documentId, userId);
        ProjectDocumentAttachment attachment = requireAttachment(documentId, attachmentId);
        // 对象删除失败时保留元数据，避免数据库宣称附件已删除但对象仍无法追踪。
        objectStorageService.deleteObjectByKey(attachment.getObjectKey());
        if (attachment.getThumbnailObjectKey() != null) {
            objectStorageService.deleteObjectByKey(attachment.getThumbnailObjectKey());
        }
        attachmentMapper.deleteById(attachmentId);
    }

    /**
     * 跨文档粘贴图片时把源附件对象复制到目标文档，保证图片身份与文档归属一致。
     * 以源附件 ID 作为 sourceId 幂等，重复粘贴不会产生多份对象。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentAttachmentResponse cloneToDocument(Long documentId, Long sourceAttachmentId, Long userId) {
        ProjectDocument targetDocument = requireDocument(documentId, userId);
        ProjectDocumentAttachment source = attachmentMapper.selectById(sourceAttachmentId);
        if (source == null) {
            throw new ResourceNotFoundException("文档附件不存在: " + sourceAttachmentId);
        }
        // 复制方必须同时能访问源文档，避免借已知 assetId 越权搬运对象。
        requireDocument(source.getDocumentId(), userId);
        if (!isImageContentType(source.getContentType())) {
            throw new IllegalArgumentException("只有图片附件支持跨文档复制");
        }
        // 同文档复制的语义等价于“已存在”，直接返回原附件，保证前端归一化重跑幂等。
        if (documentId.equals(source.getDocumentId())) {
            return toResponse(source);
        }
        String cloneSourceId = CLONE_SOURCE_PREFIX + sourceAttachmentId;
        ProjectDocumentAttachment existing = findBySourceId(documentId, cloneSourceId);
        if (existing != null) {
            return toResponse(existing);
        }

        documentMapper.lockProjectDocumentMutations(targetDocument.getProjectId());
        existing = findBySourceId(documentId, cloneSourceId);
        if (existing != null) {
            return toResponse(existing);
        }
        String objectKey = objectStorageService.copyProjectDocumentAttachmentObject(
                source.getObjectKey(), source.getFileName(), targetDocument.getProjectId(), documentId);
        String thumbnailKey = null;
        try {
            if (source.getThumbnailObjectKey() != null) {
                thumbnailKey = objectStorageService.copyProjectDocumentAttachmentObject(
                        source.getThumbnailObjectKey(), source.getFileName(),
                        targetDocument.getProjectId(), documentId);
            }
            ProjectDocumentAttachment clone = new ProjectDocumentAttachment();
            clone.setProjectId(targetDocument.getProjectId());
            clone.setDocumentId(documentId);
            clone.setSourceId(cloneSourceId);
            clone.setObjectKey(objectKey);
            clone.setFileName(source.getFileName());
            clone.setFileSize(source.getFileSize());
            clone.setContentType(source.getContentType());
            clone.setSha256(source.getSha256());
            clone.setWidth(source.getWidth());
            clone.setHeight(source.getHeight());
            clone.setThumbnailObjectKey(thumbnailKey);
            clone.setThumbnailFileSize(source.getThumbnailFileSize());
            clone.setThumbnailContentType(source.getThumbnailContentType());
            clone.setCreatedAt(BeijingTime.now());
            attachmentMapper.insert(clone);
            return toResponse(requireAttachment(documentId, clone.getId()));
        } catch (RuntimeException | Error persistenceFailure) {
            deleteObjectQuietly(objectKey, persistenceFailure);
            if (thumbnailKey != null) {
                deleteObjectQuietly(thumbnailKey, persistenceFailure);
            }
            throw persistenceFailure;
        }
    }

    public void deleteForProject(Long projectId) {
        List<ProjectDocumentAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .eq(ProjectDocumentAttachment::getProjectId, projectId));
        for (ProjectDocumentAttachment attachment : attachments) {
            objectStorageService.deleteObjectByKey(attachment.getObjectKey());
            if (attachment.getThumbnailObjectKey() != null) {
                objectStorageService.deleteObjectByKey(attachment.getThumbnailObjectKey());
            }
        }
        attachmentMapper.delete(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                .eq(ProjectDocumentAttachment::getProjectId, projectId));
    }

    private List<ProjectDocumentAttachment> listAttachments(Long documentId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                .eq(ProjectDocumentAttachment::getDocumentId, documentId)
                .orderByAsc(ProjectDocumentAttachment::getCreatedAt)
                .orderByAsc(ProjectDocumentAttachment::getId));
    }

    private ProjectDocument requireDocument(Long documentId, Long userId) {
        ProjectDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        }
        accessGuard.requireMember(document.getProjectId(), userId);
        return document;
    }

    private ProjectDocumentAttachment requireAttachment(Long documentId, Long attachmentId) {
        ProjectDocumentAttachment attachment = attachmentMapper.selectOne(
                new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .eq(ProjectDocumentAttachment::getId, attachmentId)
                        .eq(ProjectDocumentAttachment::getDocumentId, documentId));
        if (attachment == null) {
            throw new ResourceNotFoundException("文档附件不存在: " + attachmentId);
        }
        return attachment;
    }

    private ProjectDocumentAttachment findBySourceId(Long documentId, String sourceId) {
        return attachmentMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                .eq(ProjectDocumentAttachment::getDocumentId, documentId)
                .eq(ProjectDocumentAttachment::getSourceId, sourceId));
    }

    private ProjectDocumentAttachmentResponse toResponse(ProjectDocumentAttachment attachment) {
        return new ProjectDocumentAttachmentResponse(
                attachment.getId(),
                attachment.getProjectId(),
                attachment.getDocumentId(),
                attachment.getSourceId(),
                attachment.getFileName(),
                attachment.getFileSize(),
                attachment.getContentType(),
                attachment.getSha256(),
                attachment.getWidth(),
                attachment.getHeight(),
                buildAssetUrl(attachment, THUMBNAIL_VARIANT),
                buildDownloadUrl(attachment),
                BeijingTime.atOffset(attachment.getCreatedAt()));
    }

    private DocumentImageAsset toImageAsset(ProjectDocumentAttachment attachment) {
        return new DocumentImageAsset(
                attachment.getId(),
                attachment.getSha256(),
                attachment.getWidth(),
                attachment.getHeight(),
                buildAssetUrl(attachment, THUMBNAIL_VARIANT),
                buildAssetUrl(attachment, ORIGINAL_VARIANT));
    }

    private String buildDownloadUrl(ProjectDocumentAttachment attachment) {
        return "/api/project-documents/" + attachment.getDocumentId()
                + "/attachments/" + attachment.getId() + "/download";
    }

    private String buildAssetUrl(ProjectDocumentAttachment attachment, String variant) {
        if (THUMBNAIL_VARIANT.equals(variant) && attachment.getThumbnailObjectKey() == null) {
            return null;
        }
        return "/api/document-assets/" + attachment.getDocumentId()
                + "/" + attachment.getId()
                + "/" + attachment.getSha256()
                + "/" + variant;
    }

    private static String buildAssetEtag(String contentHash) {
        return "\"sha256:" + contentHash + "\"";
    }

    private static String buildThumbnailEtag(String objectKey) {
        String version = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        return "\"thumbnail-key:" + version + "\"";
    }

    private static boolean isImageContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private void requireValidFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文档附件不能为空");
        }
        if (maxBytes <= 0 || file.getSize() > maxBytes) {
            throw new IllegalArgumentException("文档附件超过大小限制");
        }
    }

    private String normalizeSourceId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        String normalized = sourceId.trim();
        if (normalized.length() > 512) {
            throw new IllegalArgumentException("附件来源标识不能超过 512 个字符");
        }
        return normalized;
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String normalized = fileName.trim();
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }

    private DocumentImageProcessor.ImageMetadata analyzeImage(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return imageProcessor.analyzeStream(input);
        } catch (IOException e) {
            throw new IllegalStateException("读取文档图片失败", e);
        }
    }

    private String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("读取文档附件失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }

    private void deleteObjectQuietly(String objectKey, Throwable primary) {
        try {
            objectStorageService.deleteObjectByKey(objectKey);
        } catch (RuntimeException | Error compensationFailure) {
            primary.addSuppressed(compensationFailure);
        }
    }
}
