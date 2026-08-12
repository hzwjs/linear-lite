package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.ConfigurePiAgentResponse;
import com.linearlite.server.dto.PiAgentStatusResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.AgentProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectAgentController {
    private final AgentProvisioningService provisioningService;

    public ProjectAgentController(AgentProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping("/{projectId}/pi-agent/status")
    public ResponseEntity<ApiResponse<PiAgentStatusResponse>> status(
            HttpServletRequest request,
            @PathVariable Long projectId) {
        Long operatorId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(provisioningService.getStatus(projectId, operatorId)));
    }

    @PostMapping("/{projectId}/pi-agent")
    public ResponseEntity<ApiResponse<ConfigurePiAgentResponse>> configure(
            HttpServletRequest request,
            @PathVariable Long projectId) {
        Long operatorId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(provisioningService.configurePi(projectId, operatorId)));
    }
}
