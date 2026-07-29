package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.ProjectContentSearchResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectContentSearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final ProjectContentSearchService searchService;

    public SearchController(ProjectContentSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectContentSearchResponse>>> search(
            HttpServletRequest request, @RequestParam String query) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        return ResponseEntity.ok(ApiResponse.success(searchService.search(query, userId)));
    }
}
