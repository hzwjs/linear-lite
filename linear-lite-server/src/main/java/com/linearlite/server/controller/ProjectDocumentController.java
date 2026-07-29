package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.MoveProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentRevisionResponse;
import com.linearlite.server.dto.ProjectDocumentRevisionSummary;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.dto.RestoreProjectDocumentRevisionRequest;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectDocumentQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProjectDocumentController {
    private final ProjectDocumentQueryService queryService;
    private final ProjectDocumentCommandService commandService;

    public ProjectDocumentController(
            ProjectDocumentQueryService queryService,
            ProjectDocumentCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping("/projects/{projectId}/documents/tree")
    public ResponseEntity<ApiResponse<List<ProjectDocumentTreeNode>>> tree(
            HttpServletRequest request, @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.listTree(projectId, userId(request), false)));
    }

    @GetMapping("/projects/{projectId}/documents/archive")
    public ResponseEntity<ApiResponse<List<ProjectDocumentTreeNode>>> archiveTree(
            HttpServletRequest request, @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.listTree(projectId, userId(request), true)));
    }

    @PostMapping("/projects/{projectId}/documents")
    public ResponseEntity<ApiResponse<ProjectDocumentResponse>> create(
            HttpServletRequest request,
            @PathVariable Long projectId,
            @RequestBody CreateProjectDocumentRequest body) {
        return ResponseEntity.ok(ApiResponse.success(commandService.create(projectId, body, userId(request))));
    }

    @GetMapping("/project-documents/{documentId}")
    public ResponseEntity<ApiResponse<ProjectDocumentResponse>> detail(
            HttpServletRequest request, @PathVariable Long documentId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getDocument(documentId, userId(request))));
    }

    @PutMapping("/project-documents/{documentId}")
    public ResponseEntity<ApiResponse<ProjectDocumentResponse>> update(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @RequestBody UpdateProjectDocumentRequest body) {
        return ResponseEntity.ok(ApiResponse.success(commandService.update(documentId, body, userId(request))));
    }

    @PutMapping("/project-documents/{documentId}/position")
    public ResponseEntity<ApiResponse<Void>> move(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @RequestBody MoveProjectDocumentRequest body) {
        commandService.move(documentId, body, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/project-documents/{documentId}/archive")
    public ResponseEntity<ApiResponse<Void>> archive(
            HttpServletRequest request, @PathVariable Long documentId) {
        commandService.archive(documentId, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/project-documents/{documentId}/restore")
    public ResponseEntity<ApiResponse<Void>> restore(
            HttpServletRequest request, @PathVariable Long documentId) {
        commandService.restore(documentId, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/project-documents/{documentId}/revisions")
    public ResponseEntity<ApiResponse<List<ProjectDocumentRevisionSummary>>> revisions(
            HttpServletRequest request, @PathVariable Long documentId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.listRevisions(documentId, userId(request))));
    }

    @GetMapping("/project-documents/{documentId}/revisions/{version}")
    public ResponseEntity<ApiResponse<ProjectDocumentRevisionResponse>> revision(
            HttpServletRequest request, @PathVariable Long documentId, @PathVariable Long version) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getRevision(documentId, version, userId(request))));
    }

    @PostMapping("/project-documents/{documentId}/revisions/{version}/restore")
    public ResponseEntity<ApiResponse<ProjectDocumentResponse>> restoreRevision(
            HttpServletRequest request,
            @PathVariable Long documentId,
            @PathVariable Long version,
            @RequestBody RestoreProjectDocumentRevisionRequest body) {
        return ResponseEntity.ok(ApiResponse.success(commandService.restoreRevision(
                documentId, version, body == null ? null : body.expectedVersion(), userId(request))));
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
