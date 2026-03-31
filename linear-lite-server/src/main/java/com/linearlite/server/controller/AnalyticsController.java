package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.AnalyticsQuery;
import com.linearlite.server.dto.AnalyticsSummaryResponse;
import com.linearlite.server.dto.TaskSnapshotPageResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目统计 API：聚合统计（summary）与任务明细分页（tasks）。
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AnalyticsSummaryResponse>> getSummary(
            HttpServletRequest request, AnalyticsQuery query) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        AnalyticsSummaryResponse data = analyticsService.getSummary(query.getProjectId(), userId, query);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<TaskSnapshotPageResponse>> getTasks(
            HttpServletRequest request, AnalyticsQuery query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        TaskSnapshotPageResponse data = analyticsService.getTaskSnapshot(
                query.getProjectId(), userId, query, page, pageSize);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
