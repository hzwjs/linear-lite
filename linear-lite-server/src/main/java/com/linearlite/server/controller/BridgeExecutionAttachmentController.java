package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.BridgeAttachRequest;
import com.linearlite.server.dto.BridgeAttachResponse;
import com.linearlite.server.service.BridgeExecutionAttachmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本机 Bridge 的一次性执行绑定入口，不创建持久化凭证。 */
@RestController
@RequestMapping("/api/bridge/executions")
public class BridgeExecutionAttachmentController {
    private final BridgeExecutionAttachmentService attachmentService;

    public BridgeExecutionAttachmentController(BridgeExecutionAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/attach")
    public ResponseEntity<ApiResponse<BridgeAttachResponse>> attach(@RequestBody BridgeAttachRequest request) {
        BridgeExecutionAttachmentService.Attachment attachment = attachmentService.attach(request.getAttachmentCode());
        return ResponseEntity.ok(ApiResponse.success(new BridgeAttachResponse(
                attachment.token(), attachment.executionId())));
    }
}
