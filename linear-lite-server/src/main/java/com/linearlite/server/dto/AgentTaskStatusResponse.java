package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record AgentTaskStatusResponse(
        String executionId,
        Long jobId,
        String sessionStatus,
        String jobStatus,
        String sourceType,
        String errorMessage,
        LocalDateTime updatedAt) {
}
