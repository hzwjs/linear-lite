package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.CollaborationApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 协作接口使用认证用户与共享输入契约。 */
@RestController
@RequestMapping("/api/collaboration")
public class TaskCollaborationController {
    private final CollaborationApiService api;

    public TaskCollaborationController(CollaborationApiService api) { this.api = api; }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson(HttpMessageNotReadableException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, "协作参数JSON无效"));
    }

    @PostMapping("/bindings/save")
    public ResponseEntity<ApiResponse<JsonNode>> saveBinding(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("save_project_device_binding", request, body);
    }

    @PostMapping("/bindings/get")
    public ResponseEntity<ApiResponse<JsonNode>> getBinding(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("get_project_device_binding", request, body);
    }

    @PostMapping("/bindings/delete")
    public ResponseEntity<ApiResponse<JsonNode>> deleteBinding(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("delete_project_device_binding", request, body);
    }

    @PostMapping("/bindings/list")
    public ResponseEntity<ApiResponse<JsonNode>> listBindings(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("list_project_device_bindings", request, body);
    }

    @PostMapping("/inputs/get")
    public ResponseEntity<ApiResponse<JsonNode>> getInputs(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("get_task_collaboration_inputs", request, body);
    }

    private ResponseEntity<ApiResponse<JsonNode>> invoke(String name, HttpServletRequest request, JsonNode body) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        JsonNode result = api.invoke(name, body, userId);
        return ResponseEntity.ok().header("X-Linear-Lite-Server-Time", java.time.Instant.now().toString())
                .header("Cache-Control", "no-store").body(ApiResponse.success(result));
    }
}
