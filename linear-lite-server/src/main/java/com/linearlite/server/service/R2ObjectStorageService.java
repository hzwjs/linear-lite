package com.linearlite.server.service;

import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.ImageUploadResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "app.storage.r2", name = "enabled", havingValue = "true")
public class R2ObjectStorageService implements ObjectStorageService {

    static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    private final R2StorageClient storageClient;
    private final R2StorageProperties properties;

    public R2ObjectStorageService(R2StorageClient storageClient, R2StorageProperties properties) {
        this.storageClient = storageClient;
        this.properties = properties;
    }

    @Override
    public ImageUploadResponse uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片文件不能为空");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (!isSupportedImageType(contentType)) {
            throw new IllegalArgumentException("仅支持 png/jpg/jpeg/webp/gif/svg 图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        String key = buildObjectKey(file.getOriginalFilename());
        byte[] bytes = readBytes(file);
        storageClient.putObject(properties.getBucket(), key, contentType, bytes);
        return new ImageUploadResponse(buildPublicUrl(key), key);
    }

    @Override
    public ImageUploadResponse uploadAttachment(MultipartFile file, long taskId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("附件文件不能为空");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("附件大小不能超过 10MB");
        }
        String contentType = normalizeContentType(file.getContentType());
        if (contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        String key = "attachments/" + taskId + "/" + UUID.randomUUID() + "-" + sanitizeFilename(file.getOriginalFilename(), "file.bin");
        byte[] bytes = readBytes(file);
        storageClient.putObject(properties.getBucket(), key, contentType, bytes);
        return new ImageUploadResponse(buildPublicUrl(key), key);
    }

    @Override
    public byte[] getObjectByKey(String key) {
        return storageClient.getObject(properties.getBucket(), key);
    }

    @Override
    public void deleteObjectByKey(String key) {
        storageClient.deleteObject(properties.getBucket(), key);
    }

    String buildObjectKey(String originalFilename) {
        String normalizedFilename = sanitizeFilename(originalFilename, "image.bin");
        String datePrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return "task-images/" + datePrefix + "/" + UUID.randomUUID() + "-" + normalizedFilename;
    }

    String buildPublicUrl(String key) {
        String baseUrl = properties.getPublicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("R2 public base URL is not configured");
        }
        return baseUrl.replaceAll("/+$", "") + "/" + key;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传图片失败", e);
        }
    }

    private static String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSupportedImageType(String contentType) {
        return contentType.equals("image/png")
                || contentType.equals("image/jpeg")
                || contentType.equals("image/webp")
                || contentType.equals("image/gif")
                || contentType.equals("image/svg+xml");
    }

    private static String sanitizeFilename(String originalFilename, String fallback) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return fallback;
        }
        String trimmed = originalFilename.trim().replace("\\", "/");
        String basename = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        String sanitized = basename.replaceAll("[^A-Za-z0-9._-]", "-");
        if (sanitized.isBlank()) {
            return fallback;
        }
        return sanitized;
    }
}
