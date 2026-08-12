package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record AgentJobClaimResponse(
        Long jobId,
        String executionId,
        Long sessionId,
        Long projectId,
        String projectName,
        String taskKey,
        String taskTitle,
        String taskDescription,
        String sourceType,
        Long sourceCommentId,
        String prompt,
        LocalDateTime leaseUntil) {
}
