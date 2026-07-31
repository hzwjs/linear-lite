package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateGitHubRepositoryRequest;
import com.linearlite.server.dto.GitHubRepositoryResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.GitHubProjectRepositoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 项目 GitHub 仓库配置。 */
@RestController
@RequestMapping("/api/projects/{projectId}/github-repositories")
public class ProjectGitHubRepositoryController {
    private final GitHubProjectRepositoryService service;
    public ProjectGitHubRepositoryController(GitHubProjectRepositoryService service) { this.service = service; }
    @GetMapping public ResponseEntity<ApiResponse<List<GitHubRepositoryResponse>>> list(HttpServletRequest r, @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(service.list(projectId, userId(r))));
    }
    @PostMapping public ResponseEntity<ApiResponse<GitHubRepositoryResponse>> create(HttpServletRequest r, @PathVariable Long projectId, @RequestBody CreateGitHubRepositoryRequest body) {
        return ResponseEntity.ok(ApiResponse.success(service.create(projectId, userId(r), body == null ? null : body.getRepositoryUrl())));
    }
    @PostMapping("/{repositoryId}/webhook-secret/reset") public ResponseEntity<ApiResponse<GitHubRepositoryResponse>> reset(HttpServletRequest r, @PathVariable Long projectId, @PathVariable Long repositoryId) {
        return ResponseEntity.ok(ApiResponse.success(service.resetWebhookSecret(projectId, repositoryId, userId(r))));
    }
    @DeleteMapping("/{repositoryId}") public ResponseEntity<ApiResponse<Void>> delete(HttpServletRequest r, @PathVariable Long projectId, @PathVariable Long repositoryId) {
        service.delete(projectId, repositoryId, userId(r)); return ResponseEntity.ok(ApiResponse.success());
    }
    private Long userId(HttpServletRequest r) { return (Long) r.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID); }
}
