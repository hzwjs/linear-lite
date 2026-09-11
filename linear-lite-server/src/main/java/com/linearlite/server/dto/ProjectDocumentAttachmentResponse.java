package com.linearlite.server.dto;

import java.time.OffsetDateTime;

public record ProjectDocumentAttachmentResponse(
        Long id,
        Long projectId,
        Long documentId,
        String sourceId,
        String fileName,
        Long fileSize,
        String contentType,
        String sha256,
        Integer width,
        Integer height,
        String thumbnailUrl,
        String url,
        OffsetDateTime createdAt) {
}
