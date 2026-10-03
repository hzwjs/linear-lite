package com.linearlite.server.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 分片缓存加速校验；R2 对象 ETag 和会话大小决定缓存内容是否可用。 */
@Service
public class DocumentUploadPartCache {
    private final Path root;

    @Autowired
    public DocumentUploadPartCache() { this(Path.of(System.getProperty("java.io.tmpdir"), "linear-document-attachment-uploads")); }
    DocumentUploadPartCache(Path root) { this.root = root; }

    public record VerifiedFile(String sha256, DocumentImageProcessor.ImageMetadata image) { }

    public void put(String sessionId, int number, byte[] bytes) throws IOException {
        Path directory = directory(sessionId);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "part-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, directory.resolve(number + ".part"), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public VerifiedFile verify(ProjectDocumentMultipartUploadService.Session session, String etag,
            Supplier<InputStream> object, DocumentImageProcessor images) throws Exception {
        int count = (int) ((session.fileSize() + ProjectDocumentMultipartUploadService.PART_SIZE - 1)
                / ProjectDocumentMultipartUploadService.PART_SIZE);
        List<Path> parts = new ArrayList<>();
        boolean completeCache = true;
        for (int number = 1; number <= count; number++) {
            Path path = directory(session.id()).resolve(number + ".part");
            parts.add(path);
            long size = Math.min(ProjectDocumentMultipartUploadService.PART_SIZE,
                    session.fileSize() - (number - 1L) * ProjectDocumentMultipartUploadService.PART_SIZE);
            if (!Files.isRegularFile(path) || Files.size(path) != size) completeCache = false;
        }
        Path imageFile = session.contentType().startsWith("image/") || "application/octet-stream".equals(session.contentType())
                ? Files.createTempFile("document-upload-image-", ".tmp") : null;
        try (InputStream input = completeCache ? openParts(parts) : object.get();
             OutputStream imageOutput = imageFile == null ? OutputStream.nullOutputStream() : Files.newOutputStream(imageFile)) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            MessageDigest composite = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            for (int number = 1; number <= count; number++) {
                MessageDigest partMd5 = MessageDigest.getInstance("MD5");
                long remaining = Math.min(ProjectDocumentMultipartUploadService.PART_SIZE,
                        session.fileSize() - (number - 1L) * ProjectDocumentMultipartUploadService.PART_SIZE);
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) throw new IllegalArgumentException("附件实际大小与上传声明不一致");
                    sha256.update(buffer, 0, read);
                    partMd5.update(buffer, 0, read);
                    imageOutput.write(buffer, 0, read);
                    remaining -= read;
                }
                composite.update(partMd5.digest());
            }
            if (input.read() != -1) throw new IllegalArgumentException("附件实际大小与上传声明不一致");
            String expected = HexFormat.of().formatHex(composite.digest()) + "-" + count;
            // R2 multipart ETag = MD5(各分片 MD5 的二进制拼接)-分片数，不能把 ETag 当整文件 SHA-256。
            if (etag == null || !expected.equals(etag.replace("\"", ""))) {
                delete(session.id());
                throw new IllegalArgumentException("附件缓存与 R2 对象校验值不一致，请重试");
            }
            imageOutput.close();
            DocumentImageProcessor.ImageMetadata image = null;
            if (imageFile != null) {
                try (InputStream imageInput = Files.newInputStream(imageFile)) { image = images.analyzeStream(imageInput); }
            }
            return new VerifiedFile(HexFormat.of().formatHex(sha256.digest()), image);
        } finally { if (imageFile != null) Files.deleteIfExists(imageFile); }
    }

    private InputStream openParts(List<Path> paths) throws IOException {
        List<InputStream> streams = new ArrayList<>();
        try {
            for (Path path : paths) streams.add(Files.newInputStream(path));
            return new SequenceInputStream(Collections.enumeration(streams));
        } catch (IOException failure) {
            for (InputStream stream : streams) stream.close();
            throw failure;
        }
    }

    public void delete(String sessionId) throws IOException {
        Path directory = directory(sessionId);
        if (!Files.exists(directory)) return;
        try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(directory);
    }

    public void cleanup(Instant cutoff, Set<String> activeSessions) throws IOException {
        if (!Files.exists(root)) return;
        try (var directories = Files.list(root)) {
            for (Path path : directories.toList()) {
                if (!activeSessions.contains(path.getFileName().toString()) && Files.isDirectory(path)
                        && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) delete(path.getFileName().toString());
            }
        }
    }

    private Path directory(String id) {
        if (!id.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("上传会话缓存标识无效");
        return root.resolve(id);
    }
}
