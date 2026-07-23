package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.EmailSettingsResponse;
import com.linearlite.server.dto.UpdateEmailSettingsRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectEmailPreferenceService;
import com.linearlite.server.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{id}/email-settings")
public class ProjectEmailPreferenceController {

    private static final List<String> SUPPORTED_SCENARIOS = List.of(
            ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);

    private final ProjectEmailPreferenceService preferenceService;
    private final ProjectService projectService;

    public ProjectEmailPreferenceController(
            ProjectEmailPreferenceService preferenceService,
            ProjectService projectService) {
        this.preferenceService = preferenceService;
        this.projectService = projectService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmailSettingsResponse>>> list(@PathVariable("id") Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(List.of(
                new EmailSettingsResponse(
                        ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY,
                        preferenceService.isEnabled(projectId,
                                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY)))));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable("id") Long projectId,
            @RequestBody UpdateEmailSettingsRequest request,
            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        projectService.requireProjectMember(projectId, userId);
        Project project = projectService.loadProject(projectId);
        if (!userId.equals(project.getCreatorId())) {
            throw new ForbiddenOperationException("只有项目创建者可以修改邮件设置");
        }
        if (request.getItems() == null) {
            throw new IllegalArgumentException("缺少邮件设置项");
        }
        for (UpdateEmailSettingsRequest.Item item : request.getItems()) {
            if (item.getScenarioKey() == null || !SUPPORTED_SCENARIOS.contains(item.getScenarioKey())) {
                throw new IllegalArgumentException("不支持的场景: " + item.getScenarioKey());
            }
            preferenceService.setEnabled(projectId, item.getScenarioKey(), Boolean.TRUE.equals(item.getEnabled()));
        }
        return ResponseEntity.ok(ApiResponse.success());
    }
}
