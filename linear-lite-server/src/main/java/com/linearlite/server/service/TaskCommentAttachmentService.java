package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.AttachmentDownload;
import com.linearlite.server.dto.TaskCommentImageResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskCommentAttachment;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskAttachmentMapper;
import com.linearlite.server.mapper.TaskCommentAttachmentMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskCommentAttachmentService {
    public static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private final TaskPermissionGuard permissions;
    private final TaskAttachmentMapper taskAttachments;
    private final TaskCommentAttachmentMapper commentAttachments;
    private final TaskMapper tasks;
    private final ObjectStorageService storage;

    public TaskCommentAttachmentService(TaskPermissionGuard permissions, TaskAttachmentMapper taskAttachments,
            TaskCommentAttachmentMapper commentAttachments, TaskMapper tasks,
            ObjectStorageService storage) {
        this.permissions = permissions;
        this.taskAttachments = taskAttachments;
        this.commentAttachments = commentAttachments;
        this.tasks = tasks;
        this.storage = storage;
    }

    public TaskCommentImageDraft upload(String taskKey, Long userId, String ref, String fileName,
            String contentType, byte[] bytes) {
        Task task = permissions.requireTaskAccessByKey(taskKey, userId);
        validate(ref, fileName, contentType, bytes);
        try {
            String key = storage.uploadTaskCommentImage(new ByteArrayInputStream(bytes), bytes.length,
                    fileName, contentType, task.getId(), MAX_IMAGE_BYTES);
            return new TaskCommentImageDraft(ref, fileName, contentType, bytes.length, key, null);
        } catch (Exception e) {
            throw new IllegalStateException("COMMENT_IMAGE_UPLOAD_FAILED: 图片上传失败", e);
        }
    }

    public TaskCommentImageDraft reference(String taskKey, Long userId, String ref, Long attachmentId) {
        Task task = permissions.requireTaskAccessByKey(taskKey, userId);
        var row = taskAttachments.selectOne(new LambdaQueryWrapper<com.linearlite.server.entity.TaskAttachment>()
                .eq(com.linearlite.server.entity.TaskAttachment::getId, attachmentId)
                .eq(com.linearlite.server.entity.TaskAttachment::getTaskId, task.getId()));
        if (row == null || row.getFileSize() == null || row.getFileSize() > MAX_IMAGE_BYTES) {
            throw new ResourceNotFoundException("同一任务内的图片附件不存在或超过大小限制");
        }
        String type = normalizeType(row.getContentType());
        if (!IMAGE_TYPES.contains(type)) throw new IllegalArgumentException("仅支持 PNG、JPEG、GIF、WebP 图片");
        try (InputStream input = storage.openObjectStreamByKey(row.getObjectKey())) {
            byte[] bytes = readLimited(input);
            validate(ref, row.getFileName(), type, bytes);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("附件内容不是支持的图片", e);
        }
        return new TaskCommentImageDraft(ref, row.getFileName(), type, row.getFileSize(), null, attachmentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void bind(Long commentId, Long taskId, List<TaskCommentImageDraft> drafts) {
        for (TaskCommentImageDraft draft : drafts) {
            if (draft.sourceAttachmentId() != null) {
                var source = taskAttachments.selectByIdForUpdate(draft.sourceAttachmentId(), taskId);
                if (source == null || source.getFileSize() == null || source.getFileSize() > MAX_IMAGE_BYTES
                        || !normalizeType(source.getContentType()).equals(draft.contentType())) {
                    throw new ResourceNotFoundException("同一任务内的图片附件不存在或已变化");
                }
            }
            TaskCommentAttachment row = new TaskCommentAttachment();
            row.setCommentId(commentId); row.setTaskId(taskId); row.setRef(draft.ref());
            row.setSourceAttachmentId(draft.sourceAttachmentId()); row.setObjectKey(draft.objectKey());
            row.setFileName(draft.fileName()); row.setContentType(draft.contentType());
            row.setFileSize(draft.fileSize());
            try { commentAttachments.insert(row); }
            catch (Exception e) { throw new IllegalStateException("COMMENT_IMAGE_BIND_FAILED: 图片绑定失败", e); }
        }
    }

    public Long taskId(String taskKey, Long userId) {
        return permissions.requireTaskAccessByKey(taskKey, userId).getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteCommentBindings(Long commentId) {
        List<TaskCommentAttachment> rows = commentAttachments.selectList(new LambdaQueryWrapper<TaskCommentAttachment>()
                .eq(TaskCommentAttachment::getCommentId, commentId));
        commentAttachments.delete(new LambdaQueryWrapper<TaskCommentAttachment>()
                .eq(TaskCommentAttachment::getCommentId, commentId));
        Runnable cleanup = () -> rows.stream().map(TaskCommentAttachment::getObjectKey).filter(key -> key != null)
                .forEach(key -> { try { storage.deleteObjectByKey(key); } catch (RuntimeException ignored) { } });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else cleanup.run();
    }

    public boolean isTaskAttachmentReferenced(Long attachmentId) {
        return commentAttachments.selectCount(new LambdaQueryWrapper<TaskCommentAttachment>()
                .eq(TaskCommentAttachment::getSourceAttachmentId, attachmentId)) > 0;
    }

    public List<TaskCommentImageResponse> list(Long commentId) {
        return commentAttachments.selectList(new LambdaQueryWrapper<TaskCommentAttachment>()
                .eq(TaskCommentAttachment::getCommentId, commentId).orderByAsc(TaskCommentAttachment::getId))
                .stream().map(this::response).toList();
    }

    public Map<Long, List<TaskCommentImageResponse>> listByComments(List<Long> commentIds) {
        if (commentIds.isEmpty()) return Map.of();
        return commentAttachments.selectList(new LambdaQueryWrapper<TaskCommentAttachment>()
                .in(TaskCommentAttachment::getCommentId, commentIds).orderByAsc(TaskCommentAttachment::getId))
                .stream().collect(Collectors.groupingBy(TaskCommentAttachment::getCommentId,
                        Collectors.mapping(this::response, Collectors.toList())));
    }

    public AttachmentDownload resolve(Long commentId, Long imageId, Long userId) {
        TaskCommentAttachment image = commentAttachments.selectOne(new LambdaQueryWrapper<TaskCommentAttachment>()
                .eq(TaskCommentAttachment::getCommentId, commentId).eq(TaskCommentAttachment::getId, imageId));
        if (image == null) throw new ResourceNotFoundException("评论图片不存在");
        Task task = tasks.selectById(image.getTaskId());
        if (task == null) throw new ResourceNotFoundException("评论图片所属任务不存在");
        permissions.requireTaskAccessByKey(task.getTaskKey(), userId);
        String objectKey = image.getObjectKey();
        if (objectKey == null) {
            var source = taskAttachments.selectOne(new LambdaQueryWrapper<com.linearlite.server.entity.TaskAttachment>()
                    .eq(com.linearlite.server.entity.TaskAttachment::getId, image.getSourceAttachmentId())
                    .eq(com.linearlite.server.entity.TaskAttachment::getTaskId, image.getTaskId()));
            if (source == null) throw new ResourceNotFoundException("评论引用的附件已删除");
            objectKey = source.getObjectKey();
        }
        return new AttachmentDownload(storage.openObjectStreamByKey(objectKey), image.getFileName(), image.getContentType(), image.getFileSize());
    }

    private TaskCommentImageResponse response(TaskCommentAttachment row) {
        TaskCommentImageResponse result = new TaskCommentImageResponse();
        result.setId(row.getId()); result.setRef(row.getRef()); result.setFileName(row.getFileName());
        result.setContentType(row.getContentType()); result.setFileSize(row.getFileSize());
        result.setSourceAttachmentId(row.getSourceAttachmentId());
        return result;
    }

    public void cleanup(List<TaskCommentImageDraft> drafts) {
        for (TaskCommentImageDraft draft : drafts) if (draft.objectKey() != null) {
            try { storage.deleteObjectByKey(draft.objectKey()); } catch (RuntimeException ignored) { }
        }
    }

    public void cleanupUnbound(Long commentId, List<TaskCommentImageDraft> drafts) {
        Set<String> boundKeys = commentAttachments.selectList(new LambdaQueryWrapper<TaskCommentAttachment>()
                .eq(TaskCommentAttachment::getCommentId, commentId)).stream()
                .map(TaskCommentAttachment::getObjectKey).filter(key -> key != null).collect(Collectors.toSet());
        cleanup(drafts.stream().filter(draft -> draft.objectKey() != null && !boundKeys.contains(draft.objectKey())).toList());
    }

    private static void validate(String ref, String name, String type, byte[] bytes) {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("图片 ref 格式无效");
        if (name == null || name.isBlank() || name.length() > 255) throw new IllegalArgumentException("图片文件名无效");
        String normalized = normalizeType(type);
        if (!IMAGE_TYPES.contains(normalized)) throw new IllegalArgumentException("仅支持 PNG、JPEG、GIF、WebP 图片");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片大小须在 1 至 6 MiB 之间");
        if (!normalized.equals("image/webp")) {
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无法解码");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > 64_000_000L) {
                        throw new IllegalArgumentException("图片尺寸无效或超过解码限制");
                    }
                    String detectedType = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "jpg", "jpeg" -> "image/jpeg";
                        case "png" -> "image/png";
                        case "gif" -> "image/gif";
                        default -> "";
                    };
                    if (!normalized.equals(detectedType) || reader.read(0) == null) {
                        throw new IllegalArgumentException("图片内容与 contentType 不匹配");
                    }
                } finally { reader.dispose(); }
            } catch (java.io.IOException e) { throw new IllegalArgumentException("图片内容无法解码", e); }
        } else if (bytes.length < 12 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            throw new IllegalArgumentException("WebP 图片签名无效");
        }
    }
    private static String normalizeType(String value) { return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); }
    private static byte[] readLimited(InputStream input) throws java.io.IOException {
        try (input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) {
                if (out.size() + count > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片超过 6 MiB"); out.write(buffer, 0, count);
            } return out.toByteArray();
        }
    }
}
