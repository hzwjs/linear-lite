package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.DocumentCollaborationDtos.SessionResponse;
import com.linearlite.server.dto.DocumentCollaborationDtos.RenewalResponse;
import com.linearlite.server.dto.DocumentCollaborationDtos.RefreshSessionRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos.TitleUpdateRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.DocumentCollaborationService;
import com.linearlite.server.service.ProjectDocumentQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DocumentCollaborationController {
    private final DocumentCollaborationService collaborationService;
    private final ProjectDocumentQueryService queryService;

    public DocumentCollaborationController(DocumentCollaborationService collaborationService,
                                           ProjectDocumentQueryService queryService) {
        this.collaborationService = collaborationService;
        this.queryService = queryService;
    }

    @PostMapping("/project-documents/{documentId}/collaboration-session")
    public ResponseEntity<ApiResponse<SessionResponse>> createSession(
            HttpServletRequest request, @PathVariable Long documentId) {
        return ResponseEntity.ok(ApiResponse.success(
                collaborationService.createSession(documentId, userId(request))));
    }

    @PostMapping("/project-documents/{documentId}/collaboration-session/refresh")
    public ResponseEntity<ApiResponse<RenewalResponse>> refreshSession(
            HttpServletRequest request, @PathVariable Long documentId,
            @RequestBody RefreshSessionRequest body) {
        return ResponseEntity.ok(ApiResponse.success(collaborationService.refreshSession(documentId,
                body == null ? null : body.sessionId(), userId(request))));
    }

    @PatchMapping("/project-documents/{documentId}/title")
    public ResponseEntity<ApiResponse<ProjectDocumentResponse>> updateTitle(
            HttpServletRequest request, @PathVariable Long documentId,
            @RequestBody TitleUpdateRequest body) {
        collaborationService.updateTitle(documentId, body == null ? null : body.expectedTitle(),
                body == null ? null : body.title(), userId(request));
        return ResponseEntity.ok(ApiResponse.success(queryService.getDocument(documentId, userId(request))));
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
