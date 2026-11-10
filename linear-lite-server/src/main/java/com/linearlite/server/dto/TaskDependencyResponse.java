package com.linearlite.server.dto;

import com.linearlite.server.entity.TaskDependency;

import java.time.LocalDateTime;

public record TaskDependencyResponse(Long id, Long taskId, Long prerequisiteTaskId, LocalDateTime createdAt) {
    public static TaskDependencyResponse from(TaskDependency dependency) {
        return new TaskDependencyResponse(dependency.getId(), dependency.getTaskId(),
                dependency.getPrerequisiteTaskId(), dependency.getCreatedAt());
    }
}
