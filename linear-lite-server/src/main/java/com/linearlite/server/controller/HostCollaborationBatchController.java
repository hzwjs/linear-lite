package com.linearlite.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.common.HostProtocol;
import com.linearlite.server.dto.CollaborationStopConfirmation;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.CollaborationApiService;
import com.linearlite.server.service.CollaborationBatchAuthorizationService;
import com.linearlite.server.service.HostCollaborationBatchService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OAuth 宿主管理归属；恢复端点只接受原批次签名与原执行证明。 */
@RestController
@RequestMapping(HostProtocol.API_PATH)
public class HostCollaborationBatchController {
    private final HostCollaborationBatchService batches;
    private final CollaborationBatchAuthorizationService authorization;
    private final CollaborationApiService api;

    public HostCollaborationBatchController(HostCollaborationBatchService batches,
            CollaborationBatchAuthorizationService authorization, CollaborationApiService api) {
        this.batches = batches; this.authorization = authorization; this.api = api;
    }

    @PostMapping("/batches/pending")
    public ResponseEntity<ApiResponse<HostCollaborationBatchService.Pending>> pending(
            HttpServletRequest request, @RequestBody JsonNode body) {
        requireFields(body, Set.of());
        return reply(batches.pending(user(request), grant(request), device(request)));
    }

    @PostMapping("/batches/context")
    public ResponseEntity<ApiResponse<HostCollaborationBatchService.Context>> context(
            HttpServletRequest request, @RequestBody JsonNode body) {
        requireFields(body, Set.of("projectId", "batchId"));
        return reply(batches.context(user(request), grant(request), device(request),
                number(body, "projectId"), text(body, "batchId")));
    }

    @PostMapping("/batches/prepare-accept")
    public ResponseEntity<ApiResponse<CollaborationBatchAuthorizationService.PreparedAcceptance>> prepareAccept(
            HttpServletRequest request, @RequestBody JsonNode body) {
        requireFields(body, Set.of("projectId", "batchId", "hostInstanceId"));
        return reply(batches.prepareAccept(user(request), grant(request), device(request), number(body, "projectId"),
                text(body, "batchId"), text(body, "hostInstanceId")));
    }

    @PostMapping("/batches/accept")
    public ResponseEntity<ApiResponse<CollaborationBatchAuthorizationService.IssuedBatch>> accept(
            HttpServletRequest request, @RequestBody JsonNode body) {
        requireFields(body, Set.of("projectId", "batchId", "hostInstanceId", "recoveryToken"));
        return reply(batches.accept(user(request), grant(request), device(request), number(body, "projectId"),
                text(body, "batchId"), text(body, "hostInstanceId"), text(body, "recoveryToken")));
    }

    @PostMapping("/batches/renew")
    public ResponseEntity<ApiResponse<HostCollaborationBatchService.Lease>> renew(
            HttpServletRequest request, @RequestBody JsonNode body) {
        requireFields(body, Set.of("batchToken"));
        return reply(batches.renew(user(request), grant(request), device(request), text(body, "batchToken")));
    }

    @PostMapping("/environment/observe")
    public ResponseEntity<ApiResponse<JsonNode>> observe(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("observe_collaboration_environment", request, body);
    }

    @PostMapping("/tasks/claim")
    public ResponseEntity<ApiResponse<JsonNode>> claim(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("claim_collaboration_task", request, body);
    }

    @PostMapping("/documents/list")
    public ResponseEntity<ApiResponse<JsonNode>> listDocuments(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("list_collaboration_documents", request, body);
    }

    @PostMapping("/documents/read")
    public ResponseEntity<ApiResponse<JsonNode>> readDocument(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("read_collaboration_document", request, body);
    }

    @PostMapping("/attempts/adopt-context")
    public ResponseEntity<ApiResponse<JsonNode>> adoptContext(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("adopt_collaboration_context", request, body);
    }

    @PostMapping("/attempts/dependencies/change")
    public ResponseEntity<ApiResponse<JsonNode>> changeDependency(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("change_collaboration_dependency", request, body);
    }

    @PostMapping("/attempts/split")
    public ResponseEntity<ApiResponse<JsonNode>> splitTask(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("split_collaboration_task", request, body);
    }

    @PostMapping("/attempts/prerequisites/review")
    public ResponseEntity<ApiResponse<JsonNode>> reviewPrerequisite(HttpServletRequest request, @RequestBody JsonNode body) {
        return invoke("review_collaboration_prerequisite", request, body);
    }

    @PostMapping("/attempts/renew")
    public ResponseEntity<ApiResponse<JsonNode>> renewAttempt(HttpServletRequest request, @RequestBody JsonNode body) {
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

    @PostMapping("/recovery/read")
    public ResponseEntity<ApiResponse<JsonNode>> recovery(@RequestBody JsonNode body) {
        return recover("get_collaboration_recovery", body);
    }

    @PostMapping("/recovery/request-stop")
    public ResponseEntity<ApiResponse<JsonNode>> requestStop(@RequestBody JsonNode body) {
        return recover("request_collaboration_stop", body);
    }

    @PostMapping("/recovery/confirm-stop")
    public ResponseEntity<ApiResponse<JsonNode>> confirmStop(@RequestBody JsonNode body) {
        return recover("confirm_collaboration_stop", body);
    }

    @PostMapping("/recovery/recover-claim")
    public ResponseEntity<ApiResponse<JsonNode>> recoverClaim(@RequestBody JsonNode body) {
        return recover("recover_unacknowledged_claim", body);
    }

    @PostMapping("/recovery/finish-accept")
    public ResponseEntity<ApiResponse<HostCollaborationBatchService.Finished>> finishAccept(@RequestBody JsonNode body) {
        requireFields(body, Set.of("recoveryToken", "confirmation"));
        return reply(batches.finishAccept(text(body, "recoveryToken"), stopConfirmation(body.get("confirmation"))));
    }

    @PostMapping("/recovery/finish")
    public ResponseEntity<ApiResponse<HostCollaborationBatchService.Finished>> finish(@RequestBody JsonNode body) {
        requireFields(body, Set.of("batchToken", "reason", "confirmation"));
        return reply(batches.finish(text(body, "batchToken"), text(body, "reason"),
                stopConfirmation(body.get("confirmation"))));
    }

    private static CollaborationStopConfirmation stopConfirmation(JsonNode confirmation) {
        requireFields(confirmation, Set.of("stopped", "detail"));
        if (!confirmation.get("stopped").isBoolean()) throw new IllegalArgumentException("停止事实无效");
        return new CollaborationStopConfirmation(confirmation.get("stopped").booleanValue(), text(confirmation, "detail"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidJson(HttpMessageNotReadableException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, "协作参数 JSON 无效"));
    }

    private ResponseEntity<ApiResponse<JsonNode>> invoke(String name, HttpServletRequest request, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("协作参数无效");
        JsonNode batch = body.get("batch");
        authorization.requireHostIdentity(text(batch, "batchToken"), user(request), grant(request), device(request));
        return reply(api.invoke(name, body, user(request)));
    }

    private ResponseEntity<ApiResponse<JsonNode>> recover(String name, JsonNode body) {
        Long userId = authorization.recoveryUserId(text(body, "batchToken"));
        return reply(api.invoke(name, body, userId));
    }

    private static void requireFields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || body.size() != fields.size())
            throw new IllegalArgumentException("协作参数无效");
        for (String name : fields) if (!body.has(name) || body.get(name).isNull())
            throw new IllegalArgumentException("协作参数无效");
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.has(field) || !body.get(field).isTextual()
                || body.get(field).textValue().isBlank()) throw new IllegalArgumentException("协作参数无效");
        return body.get(field).textValue();
    }

    private static Long number(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.has(field) || !body.get(field).isIntegralNumber()
                || !body.get(field).canConvertToLong() || body.get(field).longValue() <= 0)
            throw new IllegalArgumentException("协作参数无效");
        return body.get(field).longValue();
    }

    private static Long user(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID);
    }
    private static Long grant(HttpServletRequest request) {
        return (Long) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_HOST_GRANT_ID);
    }
    private static String device(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthFilter.REQUEST_ATTR_HOST_DEVICE_ID);
    }
    private static <T> ResponseEntity<ApiResponse<T>> reply(T value) {
        return ResponseEntity.ok().header("X-Linear-Lite-Server-Time", Instant.now().toString())
                .header("Cache-Control", "no-store").body(ApiResponse.success(value));
    }
}
