package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record AgentSessionResponse(
        String executionId,
        String taskKey,
        String status,
        String sessionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {
}
