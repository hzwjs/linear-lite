package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectDocumentAttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/project-documents/{documentId}/attachments")
public class ProjectDocumentAttachmentController {
    private final ProjectDocumentAttachmentService attachmentService;
    private final R2StorageProperties storageProperties;

    public ProjectDocumentAttachmentController(
            ProjectDocumentAttachmentService attachmentService,
            R2StorageProperties storageProperties) {
        this.attachmentService = attachmentService;
        this.storageProperties = storageProperties;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectDocumentAttachmentResponse>> upload(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceId", required = false) String sourceId) {
        if (!storageProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.fail(503, "存储服务不可用"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                attachmentService.upload(documentId, file, sourceId, userId(request))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectDocumentAttachmentResponse>>> list(
            HttpServletRequest request, @PathVariable Long documentId) {
        return ResponseEntity.ok(ApiResponse.success(attachmentService.list(documentId, userId(request))));
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @PathVariable Long attachmentId) {
        if (!storageProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        var download = attachmentService.download(documentId, attachmentId, userId(request));
        String disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        StreamingResponseBody body = outputStream -> {
            try (var input = download.stream()) {
                input.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(download.contentTypeOrDefault()))
                .contentLength(download.contentLengthOrDefault())
                .body(body);
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @PathVariable Long attachmentId) {
        attachmentService.delete(documentId, attachmentId, userId(request));
        return ResponseEntity.noContent().build();
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
