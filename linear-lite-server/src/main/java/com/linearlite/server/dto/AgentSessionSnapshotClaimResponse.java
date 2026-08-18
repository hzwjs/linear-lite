package com.linearlite.server.dto;

public record AgentSessionSnapshotClaimResponse(
        String requestId,
        String executionId,
        String piSessionId,
        Long projectId) {
}
