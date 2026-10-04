package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.MarkdownDocumentImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/documents/import-markdown")
public class ProjectDocumentImportController {
    private final MarkdownDocumentImportService imports;

    public ProjectDocumentImportController(MarkdownDocumentImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProjectDocumentResponse>> importMarkdown(HttpServletRequest request,
            @PathVariable Long projectId, @RequestParam(required = false) Long parentDocumentId,
            @RequestParam MultipartFile file, @RequestPart List<String> imagePaths,
            @RequestParam(required = false) List<MultipartFile> images) {
        return ResponseEntity.ok(ApiResponse.success(imports.importFile(projectId, parentDocumentId, file,
                imagePaths, images == null ? List.of() : images,
                (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID))));
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MarkdownDocumentImportService.Preview>> inspect(HttpServletRequest request,
            @PathVariable Long projectId, @RequestParam MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(imports.inspect(projectId, file,
                (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID))));
    }
}
