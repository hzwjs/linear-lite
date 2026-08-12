package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record AgentTaskEventResponse(
        Long id,
        String executionId,
        Long jobId,
        Long sequenceNo,
        String eventType,
        String summary,
        String payload,
        LocalDateTime createdAt) {
}
