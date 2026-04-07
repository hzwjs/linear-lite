package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CreateProjectInvitationRequest;
import com.linearlite.server.dto.CreateProjectRequest;
import com.linearlite.server.dto.TaskLabelResponse;
import com.linearlite.server.dto.UpdateProjectRequest;
import com.linearlite.server.dto.UserSummaryDto;
import com.linearlite.server.entity.Project;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.LabelService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目 API。需 JWT 保护（请求头 Authorization: Bearer &lt;token&gt;）。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final LabelService labelService;

    public ProjectController(ProjectService projectService, LabelService labelService) {
        this.projectService = projectService;
        this.labelService = labelService;
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
     * 获取项目成员列表（负责人选择用）。
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<ApiResponse<List<UserSummaryDto>>> listMembers(
            HttpServletRequest httpRequest,
            @PathVariable("id") Long projectId) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        List<UserSummaryDto> members = projectService.listMembers(projectId, userId);
        return ResponseEntity.ok(ApiResponse.success(members));
    }

    /**
     * 项目内标签列表（任务侧栏联想）；query 可选，按名称前缀过滤。
     */
    @GetMapping("/{id}/labels")
    public ResponseEntity<ApiResponse<List<TaskLabelResponse>>> listLabels(
            HttpServletRequest httpRequest,
            @PathVariable("id") Long projectId,
            @RequestParam(required = false) String query) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        List<TaskLabelResponse> list = labelService.listForProject(projectId, query, userId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * 删除项目内一条标签定义，并解除所有任务对该标签的关联。
     */
    @DeleteMapping("/{id}/labels/{labelId}")
    public ResponseEntity<ApiResponse<Void>> deleteLabel(
            HttpServletRequest httpRequest,
            @PathVariable("id") Long projectId,
            @PathVariable("labelId") Long labelId) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        labelService.deleteLabelDefinition(projectId, labelId, userId);
        return ResponseEntity.ok(ApiResponse.success());
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
