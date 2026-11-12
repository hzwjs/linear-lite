package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.CollaborationBatchDtos.*;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.CollaborationBatchControlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/collaboration/batches")
public class CollaborationBatchController {
    private final CollaborationBatchControlService service;

    public CollaborationBatchController(CollaborationBatchControlService service) { this.service = service; }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<JsonNode>> start(HttpServletRequest request, @RequestBody StartRequest input) {
        return reply(service.start(userId(request), input));
    }

    @PostMapping("/start-single")
    public ResponseEntity<ApiResponse<JsonNode>> startSingle(HttpServletRequest request, @RequestBody StartSingleRequest input) {
        return reply(service.startSingle(userId(request), input));
    }

    @PostMapping("/stop")
    public ResponseEntity<ApiResponse<JsonNode>> stop(HttpServletRequest request, @RequestBody StopRequest input) {
        return reply(service.stop(userId(request), input));
    }

    @PostMapping("/authorize-goal")
    public ResponseEntity<ApiResponse<JsonNode>> authorizeGoal(HttpServletRequest request, @RequestBody AuthorizeGoalRequest input) {
        return reply(service.authorizeGoal(userId(request), input));
    }

    @PostMapping("/list")
    public ResponseEntity<ApiResponse<Page>> list(HttpServletRequest request, @RequestBody ListRequest input) {
        return reply(service.list(userId(request), input));
    }

    @PostMapping("/get")
    public ResponseEntity<ApiResponse<Detail>> get(HttpServletRequest request, @RequestBody GetRequest input) {
        return reply(service.get(userId(request), input));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson(HttpMessageNotReadableException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, "批次参数 JSON 无效"));
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }

    private <T> ResponseEntity<ApiResponse<T>> reply(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(value));
    }
}
