package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateProjectInvitationRequest;
import com.linearlite.server.dto.CreateProjectRequest;
import com.linearlite.server.dto.UpdateProjectRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目 API。需 JWT 保护（请求头 Authorization: Bearer &lt;token&gt;）。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 获取项目列表（侧边栏用）。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Project>>> list(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        List<Project> list = projectService.list(userId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * 新建项目。请求体需包含 name、identifier。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Project>> create(
            HttpServletRequest httpRequest,
            @RequestBody CreateProjectRequest request) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        Project created = projectService.create(
                request.getName(),
                request.getIdentifier(),
                userId);
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    /**
     * 更新项目。请求体可选 name、identifier。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Project>> update(
            HttpServletRequest httpRequest,
            @PathVariable("id") Long id,
            @RequestBody UpdateProjectRequest request) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        Project updated = projectService.update(id, request.getName(), request.getIdentifier(), userId);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PostMapping("/{id}/invitations")
    public ResponseEntity<ApiResponse<Void>> invite(
            HttpServletRequest httpRequest,
            @PathVariable("id") Long id,
            @RequestBody CreateProjectInvitationRequest request) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        projectService.invite(id, userId, request.getEmail());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            HttpServletRequest httpRequest,
            @PathVariable("id") Long id) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        projectService.delete(id, userId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
