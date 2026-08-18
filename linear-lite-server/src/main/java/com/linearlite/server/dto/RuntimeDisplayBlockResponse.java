package com.linearlite.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record RuntimeDisplayBlockResponse(
        String executionId,
        Long jobId,
        String blockId,
        Long revision,
        String kind,
        String phase,
        JsonNode content,
        JsonNode tool,
        LocalDateTime createdAt) {
}
