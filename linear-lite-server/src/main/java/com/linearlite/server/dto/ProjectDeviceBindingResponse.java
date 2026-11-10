package com.linearlite.server.dto;

import com.linearlite.server.entity.ProjectDeviceBinding;

import java.time.LocalDateTime;

public record ProjectDeviceBindingResponse(Long id, Long userId, Long projectId, String deviceId,
                                           String projectDirectory, Long revision, LocalDateTime createdAt,
                                           LocalDateTime updatedAt) {
    public static ProjectDeviceBindingResponse from(ProjectDeviceBinding binding) {
        return new ProjectDeviceBindingResponse(binding.getId(), binding.getUserId(), binding.getProjectId(),
                binding.getDeviceId(), binding.getProjectDirectory(), binding.getRevision(),
                binding.getCreatedAt(), binding.getUpdatedAt());
    }
}
