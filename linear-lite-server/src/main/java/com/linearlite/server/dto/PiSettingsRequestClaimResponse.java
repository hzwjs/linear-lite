package com.linearlite.server.dto;

/** Bridge 领取的单次 Pi 设置控制指令。 */
public record PiSettingsRequestClaimResponse(
        String requestId,
        String executionId,
        String piSessionId,
        Long projectId,
        String action,
        String provider,
        String modelId,
        String level) {
}
