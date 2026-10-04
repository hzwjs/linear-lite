package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.DocumentRemoteImageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/project-documents/{documentId}/image-imports")
public class DocumentImageImportController {
    private final DocumentRemoteImageService images;
    private final R2StorageProperties storage;

    public DocumentImageImportController(DocumentRemoteImageService images, R2StorageProperties storage) {
        this.images = images;
        this.storage = storage;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectDocumentAttachmentResponse>> importImage(
            HttpServletRequest request, @PathVariable Long documentId, @RequestBody ImageImportRequest body) {
        if (!storage.isEnabled()) return ResponseEntity.status(503).body(ApiResponse.fail(503, "存储服务不可用"));
        return ResponseEntity.ok(ApiResponse.success(images.importImage(documentId, body.url(),
                (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID))));
    }

    public record ImageImportRequest(String url) { }
}
