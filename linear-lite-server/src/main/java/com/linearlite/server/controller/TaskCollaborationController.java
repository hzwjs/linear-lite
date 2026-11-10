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

    @PostMapping("/batches/issue")
    public ResponseEntity<ApiResponse<JsonNode>> issueBatch(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("issue_collaboration_batch", request, body);
    }

    @PostMapping("/inputs/replace")
    public ResponseEntity<ApiResponse<JsonNode>> replaceInputs(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("replace_task_collaboration_inputs", request, body);
    }

    @PostMapping("/inputs/get")
    public ResponseEntity<ApiResponse<JsonNode>> getInputs(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("get_task_collaboration_inputs", request, body);
    }

    @PostMapping("/environment/observe")
    public ResponseEntity<ApiResponse<JsonNode>> observe(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("observe_collaboration_environment", request, body);
    }

    @PostMapping("/tasks/claim")
    public ResponseEntity<ApiResponse<JsonNode>> claim(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("claim_collaboration_task", request, body);
    }

    @PostMapping("/attempts/expand-scopes")
    public ResponseEntity<ApiResponse<JsonNode>> expandScopes(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("expand_collaboration_write_scopes", request, body);
    }

    @PostMapping("/attempts/renew")
    public ResponseEntity<ApiResponse<JsonNode>> renew(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("renew_collaboration_attempt", request, body);
    }

    @PostMapping("/attempts/checkpoint")
    public ResponseEntity<ApiResponse<JsonNode>> checkpoint(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("append_collaboration_checkpoint", request, body);
    }

    @PostMapping("/attempts/complete")
    public ResponseEntity<ApiResponse<JsonNode>> complete(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("complete_collaboration_task", request, body);
    }

    @PostMapping("/attempts/request-stop")
    public ResponseEntity<ApiResponse<JsonNode>> requestStop(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("request_collaboration_stop", request, body);
    }

    @PostMapping("/attempts/recovery")
    public ResponseEntity<ApiResponse<JsonNode>> readRecovery(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("get_collaboration_recovery", request, body);
    }

    @PostMapping("/attempts/recover-claim")
    public ResponseEntity<ApiResponse<JsonNode>> recoverClaim(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("recover_unacknowledged_claim", request, body);
    }

    @PostMapping("/attempts/confirm-stop")
    public ResponseEntity<ApiResponse<JsonNode>> confirmStop(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("confirm_collaboration_stop", request, body);
    }

    private ResponseEntity<ApiResponse<JsonNode>> invoke(String name, HttpServletRequest request, JsonNode body) {
        Long userId = (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
        JsonNode result = api.invoke(name, body, userId);
        return ResponseEntity.ok().header("X-Linear-Lite-Server-Time", java.time.Instant.now().toString())
                .header("Cache-Control", "no-store").body(ApiResponse.success(result));
    }
}
