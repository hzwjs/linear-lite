package com.linearlite.server.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectDocumentResponse(
        Long id,
        Long projectId,
        Long parentDocumentId,
        String externalSource,
        String externalSourceId,
        String title,
        String content,
        List<DocumentImageAsset> imageAssets,
        Integer sortOrder,
        Long version,
        Long creatorId,
        Long lastEditorId,
        boolean favorited,
        LocalDateTime archivedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
