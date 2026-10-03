package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.CreateDocumentAttachmentUploadRequest;
import com.linearlite.server.dto.DocumentAttachmentUploadResponse;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@ConditionalOnProperty(prefix = "app.storage.r2", name = "enabled", havingValue = "true")
public class ProjectDocumentMultipartUploadService {
    public static final long PART_SIZE = 8L * 1024 * 1024;
    private static final Duration SESSION_LIFETIME = Duration.ofHours(24);
    private static final String PREFIX = "document-attachment-uploads/";
    private static final String TOKEN_SUBJECT = "document-attachment-upload";
    private static final Logger log = LoggerFactory.getLogger(ProjectDocumentMultipartUploadService.class);

    /** 签名会话携带唯一文件元数据，分片清单以 R2 ListParts 为准。 */
    public record Session(String id, Long documentId, Long projectId, Long userId,
                          String objectKey, String uploadId, String fileName, long fileSize, String contentType) {
        public String sourceId() { return "multipart:" + id; }
    }

    private final S3Client s3;
    private final R2StorageProperties storage;
    private final SecretKey signingKey;
    private final ObjectMapper json;
    private final ProjectDocumentMapper documents;
    private final ProjectDocumentAttachmentMapper attachments;
    private final ProjectAccessGuard access;
    private final ProjectDocumentAttachmentService attachmentService;
    private final ObjectStorageService objects;
    private final DocumentImageProcessor images;
    private final DocumentUploadPartCache partCache;
    private final long maxBytes;
    private final ConcurrentMap<String, CompletableFuture<ProjectDocumentAttachmentResponse>> finalizations = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "document-upload-finalization");
                thread.setDaemon(true);
                return thread;
            });

    public ProjectDocumentMultipartUploadService(S3Client s3,
            R2StorageProperties storage, JwtProperties jwt, ObjectMapper json,
            ProjectDocumentMapper documents, ProjectDocumentAttachmentMapper attachments,
            ProjectAccessGuard access, ProjectDocumentAttachmentService attachmentService,
            ObjectStorageService objects, DocumentImageProcessor images, DocumentUploadPartCache partCache,
            @Value("${app.storage.document-attachment-max-bytes:2147483648}") long maxBytes) {
        this.s3 = s3;
        this.storage = storage;
        this.signingKey = Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8));
        this.json = json;
        this.documents = documents;
        this.attachments = attachments;
        this.access = access;
        this.attachmentService = attachmentService;
        this.objects = objects;
        this.images = images;
        this.partCache = partCache;
        this.maxBytes = maxBytes;
    }

    public DocumentAttachmentUploadResponse create(Long documentId, Long userId, CreateDocumentAttachmentUploadRequest request) {
        ProjectDocument document = requireDocument(documentId, userId);
        if (request.fileName() == null || request.fileName().isBlank() || request.fileName().length() > 256) {
            throw new IllegalArgumentException("附件文件名不能为空且不能超过 256 个字符");
        }
        if (request.fileSize() <= 0 || request.fileSize() > maxBytes || request.fileSize() > PART_SIZE * 1000) {
            throw new IllegalArgumentException("文档附件超过大小限制或为空");
        }
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? "application/octet-stream" : request.contentType().trim().toLowerCase(Locale.ROOT);
        org.springframework.http.MediaType.parseMediaType(contentType);
        String id = UUID.randomUUID().toString();
        String key = PREFIX + document.getProjectId() + "/" + documentId + "/" + id;
        String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(storage.getBucket()).key(key).contentType(contentType)
                .metadata(Map.of("upload-session", id)).build()).uploadId();
        Session session = new Session(id, documentId, document.getProjectId(), userId,
                key, uploadId, request.fileName(), request.fileSize(), contentType);
        Instant now = Instant.now();
        // subject 不使用用户 ID；该会话令牌不能被 JwtAuthFilter 当作登录令牌使用。
        String token = Jwts.builder().subject(TOKEN_SUBJECT).claim("upload", session)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(SESSION_LIFETIME)))
                .signWith(signingKey).compact();
        return response(token, "uploading", List.of(), null);
    }

    public Session requireSession(Long documentId, Long userId, String token) {
        Session session;
        try {
            var claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
            if (!TOKEN_SUBJECT.equals(claims.getSubject())) throw new IllegalArgumentException();
            session = json.convertValue(claims.get("upload"), Session.class);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("上传会话已过期或无效，请取消后重选文件", failure);
        }
        if (!Objects.equals(documentId, session.documentId()) || !Objects.equals(userId, session.userId())) {
            throw new ForbiddenOperationException("上传会话不属于当前用户或文档");
        }
        ProjectDocument document = requireDocument(documentId, userId);
        if (!Objects.equals(document.getProjectId(), session.projectId())) {
            throw new ForbiddenOperationException("文档项目与上传会话不一致");
        }
        return session;
    }

    public void uploadPart(Session session, int partNumber, MultipartFile file) {
        if (partNumber < 1 || partNumber > partCount(session)) {
            throw new IllegalArgumentException("附件分片编号无效");
        }
        if (finalizations.containsKey(session.id())) throw new ConflictOperationException("附件处于校验和保存阶段");
        long expected = Math.min(PART_SIZE, session.fileSize() - (partNumber - 1L) * PART_SIZE);
        if (file == null || file.getSize() != expected) throw new IllegalArgumentException("附件分片大小不一致");
        try {
            // 每个分片使用 8MiB 缓冲；SDK 重试重放分片内容。
            byte[] bytes = file.getBytes();
            s3.uploadPart(UploadPartRequest.builder().bucket(storage.getBucket()).key(session.objectKey())
                    .uploadId(session.uploadId()).partNumber(partNumber).contentLength(expected).build(),
                    RequestBody.fromBytes(bytes));
            partCache.put(session.id(), partNumber, bytes);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("读取附件分片失败", failure);
        }
    }

    public DocumentAttachmentUploadResponse status(Session session, String token) {
        ProjectDocumentAttachmentResponse attachment = attachmentService.findUploadedAttachment(
                session.documentId(), session.sourceId(), session.userId());
        if (attachment != null) return response(token, "completed", List.of(), attachment);
        var future = finalizations.get(session.id());
        if (future != null) {
            if (!future.isDone()) return response(token, "processing", List.of(), null);
            try {
                return response(token, "completed", List.of(), future.join());
            } catch (CompletionException failure) {
                finalizations.remove(session.id(), future);
                throw new IllegalStateException("附件校验或保存失败，请重试", failure.getCause());
            }
        }
        try {
            List<Part> parts = listParts(session);
            return response(token, "uploading", parts.stream().map(part ->
                    new DocumentAttachmentUploadResponse.UploadedPart(part.partNumber(), part.size())).toList(), null);
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) throw failure;
            // CompleteMultipartUpload 成功但响应丢失时，对象已存在；确认请求可以继续完成入库。
            requireCompletedObject(session);
            return response(token, "uploaded", List.of(), null);
        }
    }

    public DocumentAttachmentUploadResponse complete(Session session, String token) {
        var attachment = attachmentService.findUploadedAttachment(session.documentId(), session.sourceId(), session.userId());
        if (attachment != null) return response(token, "completed", List.of(), attachment);
        var future = finalizations.computeIfAbsent(session.id(), ignored -> CompletableFuture.supplyAsync(() -> finalizeUpload(session), executor));
        future.thenAccept(ignored -> finalizations.remove(session.id(), future));
        return status(session, token);
    }

    private ProjectDocumentAttachmentResponse finalizeUpload(Session session) {
        String thumbnailKey = null;
        try {
            try {
                List<Part> parts = listParts(session);
                requireAllParts(session, parts);
                s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                        .bucket(storage.getBucket()).key(session.objectKey()).uploadId(session.uploadId())
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts.stream().map(part ->
                                CompletedPart.builder().partNumber(part.partNumber()).eTag(part.eTag()).build()).toList()).build())
                        .build());
            } catch (S3Exception failure) {
                if (failure.statusCode() != 404) throw failure;
                requireCompletedObject(session);
            }
            HeadObjectResponse object = requireCompletedObject(session);
            var verified = partCache.verify(session, object.eTag(),
                    () -> objects.openObjectStreamByKey(session.objectKey()), images);
            String contentType = session.contentType();
            var image = verified.image();
            if (image != null && image.sourceContentType() != null && "application/octet-stream".equals(contentType)) {
                contentType = image.sourceContentType();
            }
            if (image != null && image.hasThumbnail()) {
                thumbnailKey = objects.uploadProjectDocumentThumbnail(image.thumbnail(), image.thumbnailContentType(),
                        session.projectId(), session.documentId());
            }
            // 网络与哈希计算不持有项目写锁；入库方法开启短事务。
            var attachment = attachmentService.registerUploadedAttachment(session.documentId(), session.userId(), session.sourceId(),
                    session.objectKey(), session.fileName(), session.fileSize(), contentType, verified.sha256(), image, thumbnailKey);
            cleanupPartCache(session.id());
            return attachment;
        } catch (Exception failure) {
            if (thumbnailKey != null) {
                try { objects.deleteObjectByKey(thumbnailKey); }
                catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            log.error("文档附件确认失败，sessionId={}", session.id(), failure);
            throw new IllegalStateException("附件校验或保存失败", failure);
        }
    }

    public void abort(Session session) {
        if (attachmentService.findUploadedAttachment(session.documentId(), session.sourceId(), session.userId()) != null) return;
        var future = finalizations.get(session.id());
        if (future != null && !future.isDone()) throw new ConflictOperationException("附件处于校验和保存阶段，不能取消");
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(storage.getBucket())
                    .key(session.objectKey()).uploadId(session.uploadId()).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) throw failure;
        }
        objects.deleteObjectByKey(session.objectKey());
        cleanupPartCache(session.id());
        finalizations.remove(session.id());
    }

    private List<Part> listParts(Session session) {
        return s3.listParts(ListPartsRequest.builder().bucket(storage.getBucket()).key(session.objectKey())
                .uploadId(session.uploadId()).maxParts(1000).build()).parts().stream()
                .sorted(Comparator.comparingInt(Part::partNumber)).toList();
    }

    void requireAllParts(Session session, List<Part> parts) {
        if (parts.size() != partCount(session)) throw new IllegalArgumentException("附件分片尚未传完");
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            long expected = Math.min(PART_SIZE, session.fileSize() - index * PART_SIZE);
            if (part.partNumber() != index + 1 || part.size() != expected) {
                throw new IllegalArgumentException("附件分片大小或编号不一致");
            }
        }
    }

    private HeadObjectResponse requireCompletedObject(Session session) {
        HeadObjectResponse object;
        try {
            object = s3.headObject(HeadObjectRequest.builder().bucket(storage.getBucket()).key(session.objectKey()).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) throw failure;
            throw new ResourceNotFoundException("上传会话不存在，请取消后重选文件");
        }
        if (object.contentLength() != session.fileSize() || !session.id().equals(object.metadata().get("upload-session"))) {
            throw new IllegalArgumentException("附件对象与上传会话不一致");
        }
        return object;
    }

    private void cleanupPartCache(String sessionId) {
        try { partCache.delete(sessionId); }
        catch (java.io.IOException failure) { log.warn("文档上传分片缓存清理失败，sessionId={}", sessionId, failure); }
    }

    private int partCount(Session session) { return (int) ((session.fileSize() + PART_SIZE - 1) / PART_SIZE); }

    private DocumentAttachmentUploadResponse response(String token, String status,
            List<DocumentAttachmentUploadResponse.UploadedPart> parts, ProjectDocumentAttachmentResponse attachment) {
        return new DocumentAttachmentUploadResponse(token, PART_SIZE, status, parts, attachment);
    }

    private ProjectDocument requireDocument(Long id, Long userId) {
        ProjectDocument document = documents.selectById(id);
        if (document == null) throw new ResourceNotFoundException("项目文档不存在: " + id);
        access.requireMember(document.getProjectId(), userId);
        return document;
    }

    /** 过期的分片与未入库对象按新上传专属前缀清理，不触及其他附件。 */
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanupExpiredUploads() {
        Instant cutoff = Instant.now().minus(SESSION_LIFETIME);
        try {
            for (var page : s3.listMultipartUploadsPaginator(ListMultipartUploadsRequest.builder()
                    .bucket(storage.getBucket()).prefix(PREFIX).build())) {
                for (var upload : page.uploads()) {
                    if (upload.initiated().isBefore(cutoff)) s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                            .bucket(storage.getBucket()).key(upload.key()).uploadId(upload.uploadId()).build());
                }
            }
            for (var page : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(storage.getBucket()).prefix(PREFIX).build())) {
                for (var object : page.contents()) {
                    String id = object.key().substring(object.key().lastIndexOf('/') + 1);
                    var future = finalizations.get(id);
                    if (!object.lastModified().isBefore(cutoff) || future != null && !future.isDone()) continue;
                    if (attachments.selectCount(new LambdaQueryWrapper<ProjectDocumentAttachment>()
                            .eq(ProjectDocumentAttachment::getObjectKey, object.key())) == 0) {
                        objects.deleteObjectByKey(object.key());
                    }
                    finalizations.remove(id);
                }
            }
        } catch (RuntimeException failure) {
            log.error("过期文档上传清理失败", failure);
        }
        try {
            partCache.cleanup(cutoff, finalizations.entrySet().stream().filter(entry -> !entry.getValue().isDone())
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        } catch (java.io.IOException failure) { log.error("过期文档分片缓存清理失败", failure); }
    }

    @PreDestroy
    public void close() { executor.shutdown(); }
}
