package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.AttachmentDownload;
import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class ProjectDocumentAttachmentService {
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentAttachmentMapper attachmentMapper;
    private final ProjectAccessGuard accessGuard;
    private final ObjectStorageService objectStorageService;
    private final long maxBytes;

    public ProjectDocumentAttachmentService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentAttachmentMapper attachmentMapper,
            ProjectAccessGuard accessGuard,
            ObjectStorageService objectStorageService,
            @Value("${app.storage.document-attachment-max-bytes:52428800}") long maxBytes) {
        this.documentMapper = documentMapper;
        this.attachmentMapper = attachmentMapper;
        this.accessGuard = accessGuard;
        this.objectStorageService = objectStorageService;
        this.maxBytes = maxBytes;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentAttachmentResponse upload(
            Long documentId, MultipartFile file, String sourceId, Long userId) {
        ProjectDocument document = requireDocument(documentId, userId);
        requireValidFile(file);
        String normalizedSourceId = normalizeSourceId(sourceId);
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

        ImageUploadResponse uploaded = objectStorageService.uploadProjectDocumentAttachment(
                file, document.getProjectId(), documentId, maxBytes);
        ProjectDocumentAttachment attachment = new ProjectDocumentAttachment();
        attachment.setProjectId(document.getProjectId());
        attachment.setDocumentId(documentId);
        attachment.setSourceId(normalizedSourceId);
        attachment.setObjectKey(uploaded.getKey());
        attachment.setFileName(normalizeFileName(file.getOriginalFilename()));
        attachment.setFileSize(file.getSize());
        attachment.setContentType(file.getContentType());
        attachment.setSha256(sha256);
        try {
            attachmentMapper.insert(attachment);
            return toResponse(requireAttachment(documentId, attachment.getId()));
        } catch (RuntimeException | Error persistenceFailure) {
            // R2 不受数据库事务管理；元数据事务未成功返回时必须删除刚上传的对象。
            try {
                objectStorageService.deleteObjectByKey(uploaded.getKey());
            } catch (RuntimeException | Error compensationFailure) {
                persistenceFailure.addSuppressed(compensationFailure);
            }
            throw persistenceFailure;
        }
    }

    public List<ProjectDocumentAttachmentResponse> list(Long documentId, Long userId) {
        requireDocument(documentId, userId);
        return attachmentMapper.selectList(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .eq(ProjectDocumentAttachment::getDocumentId, documentId)
                        .orderByAsc(ProjectDocumentAttachment::getCreatedAt)
                        .orderByAsc(ProjectDocumentAttachment::getId))
                .stream()
                .map(this::toResponse)
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

    public void delete(Long documentId, Long attachmentId, Long userId) {
        requireDocument(documentId, userId);
        ProjectDocumentAttachment attachment = requireAttachment(documentId, attachmentId);
        // 对象删除失败时保留元数据，避免数据库宣称附件已删除但对象仍无法追踪。
        objectStorageService.deleteObjectByKey(attachment.getObjectKey());
        attachmentMapper.deleteById(attachmentId);
    }

    public void deleteForProject(Long projectId) {
        List<ProjectDocumentAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<ProjectDocumentAttachment>()
                        .eq(ProjectDocumentAttachment::getProjectId, projectId));
        for (ProjectDocumentAttachment attachment : attachments) {
            objectStorageService.deleteObjectByKey(attachment.getObjectKey());
        }
        attachmentMapper.delete(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                .eq(ProjectDocumentAttachment::getProjectId, projectId));
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
        String url = "/api/project-documents/" + attachment.getDocumentId()
                + "/attachments/" + attachment.getId() + "/download";
        return new ProjectDocumentAttachmentResponse(
                attachment.getId(),
                attachment.getProjectId(),
                attachment.getDocumentId(),
                attachment.getSourceId(),
                attachment.getFileName(),
                attachment.getFileSize(),
                attachment.getContentType(),
                attachment.getSha256(),
                url,
                attachment.getCreatedAt());
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

    private String sha256(MultipartFile file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("读取文档附件失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }
}
