package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateDocumentAttachmentUploadRequest;
import com.linearlite.server.dto.DocumentAttachmentUploadResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectDocumentMultipartUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/project-documents/{documentId}/attachment-uploads")
@ConditionalOnProperty(prefix = "app.storage.r2", name = "enabled", havingValue = "true")
public class ProjectDocumentMultipartUploadController {
    public record SessionRequest(String sessionToken) { }
    private final ProjectDocumentMultipartUploadService uploads;

    public ProjectDocumentMultipartUploadController(ProjectDocumentMultipartUploadService uploads) { this.uploads = uploads; }

    @PostMapping
    public ApiResponse<DocumentAttachmentUploadResponse> create(HttpServletRequest request, @PathVariable Long documentId,
            @RequestBody CreateDocumentAttachmentUploadRequest body) {
        return ApiResponse.success(uploads.create(documentId, userId(request), body));
    }

    @PostMapping("/status")
    public ApiResponse<DocumentAttachmentUploadResponse> status(HttpServletRequest request, @PathVariable Long documentId,
            @RequestBody SessionRequest body) {
        return ApiResponse.success(uploads.status(uploads.requireSession(documentId, userId(request), body.sessionToken()), body.sessionToken()));
    }

    @PutMapping("/parts/{partNumber}")
    public ApiResponse<Void> uploadPart(HttpServletRequest request, @PathVariable Long documentId,
            @PathVariable int partNumber, @RequestParam String sessionToken, @RequestParam MultipartFile file) {
        uploads.uploadPart(uploads.requireSession(documentId, userId(request), sessionToken), partNumber, file);
        return ApiResponse.success(null);
    }

    @PostMapping("/complete")
    public ApiResponse<DocumentAttachmentUploadResponse> complete(HttpServletRequest request, @PathVariable Long documentId,
            @RequestBody SessionRequest body) {
        return ApiResponse.success(uploads.complete(uploads.requireSession(documentId, userId(request), body.sessionToken()), body.sessionToken()));
    }

    @DeleteMapping
    public ApiResponse<Void> abort(HttpServletRequest request, @PathVariable Long documentId, @RequestBody SessionRequest body) {
        uploads.abort(uploads.requireSession(documentId, userId(request), body.sessionToken()));
        return ApiResponse.success(null);
    }

    private Long userId(HttpServletRequest request) { return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID); }
}
