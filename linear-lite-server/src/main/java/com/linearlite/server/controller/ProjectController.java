package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateProjectRequest;
import com.linearlite.server.dto.UpdateProjectRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.service.ProjectService;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResponse<List<Project>>> list() {
        List<Project> list = projectService.list();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * 新建项目。请求体需包含 name、identifier。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Project>> create(@RequestBody CreateProjectRequest request) {
        Project created = projectService.create(
                request.getName(),
                request.getIdentifier());
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    /**
     * 更新项目。请求体可选 name、identifier。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Project>> update(
            @PathVariable("id") Long id,
            @RequestBody UpdateProjectRequest request) {
        Project updated = projectService.update(id, request.getName(), request.getIdentifier());
        return ResponseEntity.ok(ApiResponse.success(updated));
    }
}
