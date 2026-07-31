package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateGitLabRepositoryRequest;
import com.linearlite.server.dto.GitLabRepositoryResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.GitLabProjectRepositoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 项目 GitLab 仓库配置，仅项目创建者能查看和变更 Webhook Secret。 */
@RestController
@RequestMapping("/api/projects/{projectId}/gitlab-repositories")
public class ProjectGitLabRepositoryController {

    private final GitLabProjectRepositoryService repositoryService;

    public ProjectGitLabRepositoryController(GitLabProjectRepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GitLabRepositoryResponse>>> list(HttpServletRequest request,
                                                                              @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(repositoryService.list(projectId, userId(request))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GitLabRepositoryResponse>> create(HttpServletRequest request,
                                                                          @PathVariable Long projectId,
                                                                          @RequestBody CreateGitLabRepositoryRequest body) {
        String repositoryUrl = body == null ? null : body.getRepositoryUrl();
        return ResponseEntity.ok(ApiResponse.success(repositoryService.create(projectId, userId(request), repositoryUrl)));
    }

    @PostMapping("/{repositoryId}/webhook-token/reset")
    public ResponseEntity<ApiResponse<GitLabRepositoryResponse>> resetWebhookToken(HttpServletRequest request,
                                                                                      @PathVariable Long projectId,
                                                                                      @PathVariable Long repositoryId) {
        return ResponseEntity.ok(ApiResponse.success(
                repositoryService.resetWebhookToken(projectId, repositoryId, userId(request))));
    }

    @DeleteMapping("/{repositoryId}")
    public ResponseEntity<ApiResponse<Void>> delete(HttpServletRequest request,
                                                     @PathVariable Long projectId,
                                                     @PathVariable Long repositoryId) {
        repositoryService.delete(projectId, repositoryId, userId(request));
        return ResponseEntity.ok(ApiResponse.success());
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
}
