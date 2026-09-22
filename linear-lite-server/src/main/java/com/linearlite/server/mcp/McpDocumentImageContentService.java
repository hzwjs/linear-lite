package com.linearlite.server.mcp;

import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.service.ProjectDocumentAttachmentService;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** MCP Markdown 图片解析与文档附件资源编排。 */
@Service
public class McpDocumentImageContentService {

    private static final Pattern REF_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final MarkdownToBlockNoteConverter converter;
    private final ProjectDocumentAttachmentService attachmentService;

    public McpDocumentImageContentService(
            MarkdownToBlockNoteConverter converter,
            ProjectDocumentAttachmentService attachmentService) {
        this.converter = converter;
        this.attachmentService = attachmentService;
    }

    public String convert(String markdown, List<McpDocumentImageInput> images, Long documentId, Long userId) {
        Set<String> refs = converter.imageReferences(markdown);
        Map<String, McpDocumentImageInput> inputs = new HashMap<>();
        for (McpDocumentImageInput image : images) {
            if (image.ref() == null || !REF_PATTERN.matcher(image.ref()).matches()) {
                throw new McpInvalidParamsException("图片 ref 必须由 1 到 64 个字母、数字、下划线或短横线组成");
            }
            if (inputs.putIfAbsent(image.ref(), image) != null) {
                throw new McpInvalidParamsException("图片 ref 不能重复: " + image.ref());
            }
        }
        if (!inputs.keySet().equals(refs)) {
            Set<String> missing = new HashSet<>(refs);
            missing.removeAll(inputs.keySet());
            Set<String> unused = new HashSet<>(inputs.keySet());
            unused.removeAll(refs);
            throw new McpInvalidParamsException(
                    "Markdown 图片引用与 images 不匹配；缺少=" + missing + "，未使用=" + unused);
        }

        Map<String, Long> assetIds = new HashMap<>();
        for (String ref : refs) {
            McpDocumentImageInput image = inputs.get(ref);
            ProjectDocumentAttachmentResponse attachment;
            if (image.isUpload()) {
                attachment = attachmentService.uploadImage(
                        documentId,
                        image.fileName(),
                        image.contentType(),
                        image.content(),
                        sourceId(image.content()),
                        userId);
            } else {
                attachment = attachmentService.cloneToDocument(documentId, image.assetId(), userId);
            }
            assetIds.put(ref, attachment.id());
        }
        return converter.convert(markdown, assetIds);
    }

    private String sourceId(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return "mcp-image:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }
}
