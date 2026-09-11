package com.linearlite.server.controller;

import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.DocumentAssetDescriptor;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectDocumentAttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 文档图片资源端点：正文只保存 assetId，图片通过本端点的不可变地址渲染。
 *
 * <p>内容哈希参与寻址，因此变体地址可长期缓存；成员关系在每次请求（含 304）都重新校验，
 * 缓存窗口固定为 60 秒，权限撤销在窗口后立即生效。
 */
@RestController
@RequestMapping("/api/document-assets")
public class DocumentAssetController {

    private static final String ASSET_CACHE_CONTROL = "private, max-age=60, must-revalidate";

    private final ProjectDocumentAttachmentService attachmentService;
    private final R2StorageProperties storageProperties;

    public DocumentAssetController(
            ProjectDocumentAttachmentService attachmentService,
            R2StorageProperties storageProperties) {
        this.attachmentService = attachmentService;
        this.storageProperties = storageProperties;
    }

    @GetMapping("/{documentId}/{assetId}/{contentHash}/{variant}")
    public ResponseEntity<StreamingResponseBody> asset(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @PathVariable Long assetId,
            @PathVariable String contentHash,
            @PathVariable String variant) {
        if (!storageProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        DocumentAssetDescriptor descriptor = attachmentService.resolveAsset(
                documentId, assetId, contentHash, variant, userId(request));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, ASSET_CACHE_CONTROL);
        // Cookie 参与缓存键：账号切换后不会在同机浏览器缓存里复用上一用户的图片。
        headers.set(HttpHeaders.VARY, HttpHeaders.COOKIE);
        headers.setETag(descriptor.etag());
        headers.setContentType(MediaType.parseMediaType(descriptor.contentTypeOrDefault()));
        if (descriptor.contentLength() >= 0) {
            headers.setContentLength(descriptor.contentLength());
        }
        // 附件行存在性与成员关系已在上方校验，304 不会绕过鉴权，也不会在对象删除后继续命中。
        if (etagMatches(request.getHeader(HttpHeaders.IF_NONE_MATCH), descriptor.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        StreamingResponseBody body = outputStream -> {
            try (var input = attachmentService.openAssetStream(descriptor.objectKey())) {
                input.transferTo(outputStream);
            }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String target = stripWeak(etag.trim());
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value) || stripWeak(value).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String stripWeak(String value) {
        return value.startsWith("W/") ? value.substring(2) : value;
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
