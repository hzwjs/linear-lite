package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentResponse(
        Long id,
        Long projectId,
        Long parentDocumentId,
        String title,
        String content,
        Integer sortOrder,
        Long version,
        Long creatorId,
        Long lastEditorId,
        LocalDateTime archivedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
