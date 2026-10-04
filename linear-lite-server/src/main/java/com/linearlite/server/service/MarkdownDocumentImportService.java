package com.linearlite.server.service;

import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class MarkdownDocumentImportService {
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private final ProjectAccessGuard accessGuard;
    private final MarkdownToBlockNoteConverter converter;
    private final ProjectDocumentCommandService documents;
    private final ProjectDocumentAttachmentService attachments;
    private final DocumentImageProcessor imageProcessor;

    public MarkdownDocumentImportService(ProjectAccessGuard accessGuard,
            MarkdownToBlockNoteConverter converter, ProjectDocumentCommandService documents,
            ProjectDocumentAttachmentService attachments, DocumentImageProcessor imageProcessor) {
        this.accessGuard = accessGuard;
        this.converter = converter;
        this.documents = documents;
        this.attachments = attachments;
        this.imageProcessor = imageProcessor;
    }

    public record Preview(List<String> imagePaths) {}
    private record ImageResource(String fileName, String contentType, byte[] bytes, String sourceId) {}

    public Preview inspect(Long projectId, MultipartFile file, Long userId) {
        accessGuard.requireMember(projectId, userId);
        return inspectMarkdown(readMarkdown(file));
    }

    private Preview inspectMarkdown(String markdown) {
        List<String> paths = converter.fileImageReferences(markdown);
        if (paths.size() > 8) throw new IllegalArgumentException("Markdown 单次导入最多包含 8 张图片");
        Map<String, Long> validationIds = new LinkedHashMap<>();
        paths.forEach(path -> validationIds.put(path, 1L));
        converter.convertFile(markdown, validationIds);
        return new Preview(paths);
    }

    public ProjectDocumentResponse importFile(Long projectId, Long parentDocumentId,
            MultipartFile file, List<String> imagePaths, List<MultipartFile> images, Long userId) {
        accessGuard.requireMember(projectId, userId);
        String markdown = readMarkdown(file);
        Preview preview = inspectMarkdown(markdown);
        Map<String, MultipartFile> files = new LinkedHashMap<>();
        if (imagePaths.size() != images.size()) throw new IllegalArgumentException("图片路径与上传文件数量不一致");
        long total = 0;
        for (int index = 0; index < imagePaths.size(); index++) {
            String path = imagePaths.get(index);
            MultipartFile image = images.get(index);
            if (files.putIfAbsent(path, image) != null) throw new IllegalArgumentException("图片路径重复: " + path);
            if (image.isEmpty() || image.getSize() > 6 * 1024 * 1024)
                throw new IllegalArgumentException("图片不能为空，单张图片不能超过 6 MiB: " + path);
            total += image.getSize();
        }
        if (total > 12 * 1024 * 1024) throw new IllegalArgumentException("导入图片总大小不能超过 12 MiB");
        if (!files.keySet().equals(new java.util.LinkedHashSet<>(preview.imagePaths())))
            throw new IllegalArgumentException("上传图片与 Markdown 引用不一致，请补齐图片资源");
        Map<String, ImageResource> resources = new LinkedHashMap<>();
        files.forEach((path, image) -> {
            try (var stream = image.getInputStream()) {
                byte[] bytes = stream.readNBytes(6 * 1024 * 1024 + 1);
                if (bytes.length > 6 * 1024 * 1024) throw new IllegalArgumentException("图片不能超过 6 MiB: " + path);
                var metadata = imageProcessor.analyze(bytes);
                if (metadata == null || !List.of("image/png", "image/jpeg", "image/gif").contains(metadata.sourceContentType()))
                    throw new IllegalArgumentException("图片须为可解码的 PNG、JPEG 或 GIF 文件: " + path);
                if ((long) metadata.width() * metadata.height() > DocumentImageProcessor.MAX_DECODE_PIXELS)
                    throw new IllegalArgumentException("图片像素总量不能超过 6400 万: " + path);
                String sourceId = "markdown-image:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
                resources.put(path, new ImageResource(image.getOriginalFilename(), metadata.sourceContentType(), bytes, sourceId));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("图片读取失败: " + path, e);
            }
        });
        String fileName = file.getOriginalFilename().replace('\\', '/');
        String title = fileName.substring(fileName.lastIndexOf('/') + 1, fileName.length() - 3).trim();
        // 资源与首版正文沿用已有事务工厂；失败时回滚文档、附件和对象存储。
        return documents.createWithContentFactory(projectId,
                new CreateProjectDocumentRequest(parentDocumentId, title, null, null, null, "BLOCKNOTE"), userId,
                documentId -> {
                    Map<String, Long> assetIds = new LinkedHashMap<>();
                    resources.forEach((path, image) -> {
                        var attachment = attachments.uploadImage(documentId, image.fileName(),
                                image.contentType(), image.bytes(), image.sourceId(), userId);
                        assetIds.put(path, attachment.id());
                    });
                    return converter.convertFile(markdown, assetIds);
                });
    }

    private String readMarkdown(MultipartFile file) {
        if (file == null) throw new IllegalArgumentException("请选择 .md 文件");
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("请选择 .md 文件");
        }
        // 浏览器提交的文件名可能带路径；标题取末段文件名。
        fileName = fileName.replace('\\', '/');
        String title = fileName.substring(fileName.lastIndexOf('/') + 1, fileName.length() - 3).trim();
        if (title.isEmpty() || title.length() > 256) {
            throw new IllegalArgumentException("文件名去掉扩展名后须为 1 至 256 个字符");
        }
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("Markdown 文件不能超过 5 MiB");
        String markdown;
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes((int) MAX_FILE_SIZE + 1);
            if (bytes.length > MAX_FILE_SIZE) throw new IllegalArgumentException("Markdown 文件不能超过 5 MiB");
            // UTF-8 解码拒绝坏字节，避免保存乱码正文。
            markdown = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Markdown 文件须使用 UTF-8 编码");
        } catch (IOException e) {
            throw new IllegalArgumentException("Markdown 文件读取失败", e);
        }
        if (markdown.startsWith("\uFEFF")) markdown = markdown.substring(1);
        if (markdown.indexOf('\0') >= 0) throw new IllegalArgumentException("Markdown 文件不能包含二进制内容");
        return markdown;
    }
}
