package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.AttachmentDownload;
import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.dto.TaskAttachmentResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskAttachment;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskAttachmentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TaskAttachmentService {

    private final TaskQueryService taskQueryService;
    private final TaskAttachmentMapper taskAttachmentMapper;
    private final ObjectStorageService objectStorageService;
    private final R2StorageProperties r2StorageProperties;
    private final long maxDownloadBytes;

    public TaskAttachmentService(TaskQueryService taskQueryService,
                                TaskAttachmentMapper taskAttachmentMapper,
                                ObjectStorageService objectStorageService,
                                R2StorageProperties r2StorageProperties,
                                @Value("${app.storage.max-download-bytes:10485760}") long maxDownloadBytes) {
        this.taskQueryService = taskQueryService;
        this.taskAttachmentMapper = taskAttachmentMapper;
        this.objectStorageService = objectStorageService;
        this.r2StorageProperties = r2StorageProperties;
        this.maxDownloadBytes = maxDownloadBytes;
    }

    public TaskAttachmentResponse upload(String taskKey, MultipartFile file, Long userId) {
        Task task = taskQueryService.getByKeyOrThrow(taskKey, userId);
        ImageUploadResponse uploaded = objectStorageService.uploadAttachment(file, task.getId());

        TaskAttachment attachment = new TaskAttachment();
        attachment.setTaskId(task.getId());
        attachment.setObjectKey(uploaded.getKey());
        attachment.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file");
        attachment.setFileSize(file.getSize());
        attachment.setContentType(file.getContentType());
        taskAttachmentMapper.insert(attachment);
        TaskAttachment inserted = taskAttachmentMapper.selectById(attachment.getId());

        TaskAttachmentResponse response = new TaskAttachmentResponse();
        response.setId(inserted.getId());
        response.setTaskId(inserted.getTaskId());
        response.setObjectKey(inserted.getObjectKey());
        response.setFileName(inserted.getFileName());
        response.setFileSize(inserted.getFileSize());
        response.setContentType(inserted.getContentType());
        response.setUrl(uploaded.getUrl());
        response.setCreatedAt(inserted.getCreatedAt());
        return response;
    }

    public List<TaskAttachmentResponse> listByTaskKey(String taskKey, Long userId) {
        Task task = taskQueryService.getByKeyOrThrow(taskKey, userId);
        List<TaskAttachment> list = taskAttachmentMapper.selectList(
                new LambdaQueryWrapper<TaskAttachment>()
                        .eq(TaskAttachment::getTaskId, task.getId())
                        .orderByAsc(TaskAttachment::getCreatedAt));
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public AttachmentDownload getAttachmentForDownload(String taskKey, Long attachmentId, Long userId) {
        Task task = taskQueryService.getByKeyOrThrow(taskKey, userId);
        TaskAttachment attachment = taskAttachmentMapper.selectOne(
                new LambdaQueryWrapper<TaskAttachment>()
                        .eq(TaskAttachment::getId, attachmentId)
                        .eq(TaskAttachment::getTaskId, task.getId()));
        if (attachment == null) {
            throw new ResourceNotFoundException("附件不存在: " + attachmentId);
        }
        if (attachment.getFileSize() > maxDownloadBytes) {
            throw new IllegalArgumentException("附件超过下载大小限制");
        }
        java.io.InputStream stream = objectStorageService.openObjectStreamByKey(attachment.getObjectKey());
        String fileName = attachment.getFileName() != null ? attachment.getFileName() : "download";
        String contentType = attachment.getContentType();
        return new AttachmentDownload(stream, fileName, contentType, attachment.getFileSize());
    }

    public void delete(String taskKey, Long attachmentId, Long userId) {
        Task task = taskQueryService.getByKeyOrThrow(taskKey, userId);
        TaskAttachment attachment = taskAttachmentMapper.selectOne(
                new LambdaQueryWrapper<TaskAttachment>()
                        .eq(TaskAttachment::getId, attachmentId)
                        .eq(TaskAttachment::getTaskId, task.getId()));
        if (attachment == null) {
            throw new ResourceNotFoundException("附件不存在: " + attachmentId);
        }
        taskAttachmentMapper.deleteById(attachmentId);
        try {
            objectStorageService.deleteObjectByKey(attachment.getObjectKey());
        } catch (Exception e) {
            // 设计：R2 删除失败仅记日志，仍返回 204
            // 如需记录日志可注入 Logger
        }
    }

    private TaskAttachmentResponse toResponse(TaskAttachment att) {
        TaskAttachmentResponse response = new TaskAttachmentResponse();
        response.setId(att.getId());
        response.setTaskId(att.getTaskId());
        response.setObjectKey(att.getObjectKey());
        response.setFileName(att.getFileName());
        response.setFileSize(att.getFileSize());
        response.setContentType(att.getContentType());
        response.setUrl(buildPublicUrl(att.getObjectKey()));
        response.setCreatedAt(att.getCreatedAt());
        return response;
    }

    private String buildPublicUrl(String key) {
        String baseUrl = r2StorageProperties.getPublicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.replaceAll("/+$", "") + "/" + key;
    }
}
