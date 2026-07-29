package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentTreeNode(
        Long id,
        Long projectId,
        Long parentDocumentId,
        String title,
        Integer sortOrder,
        Long version,
        LocalDateTime updatedAt) {
}
