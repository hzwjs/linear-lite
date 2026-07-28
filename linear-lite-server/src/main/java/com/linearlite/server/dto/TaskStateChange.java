package com.linearlite.server.dto;

import java.time.LocalDateTime;

/** Minimal authoritative state returned for an ancestor completed by the server. */
public record TaskStateChange(
        Long id,
        String taskKey,
        String status,
        Integer progressPercent,
        LocalDateTime completedAt,
        LocalDateTime updatedAt) {
}
