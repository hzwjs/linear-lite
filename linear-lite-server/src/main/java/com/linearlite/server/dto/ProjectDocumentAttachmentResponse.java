package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentAttachmentResponse(
        Long id,
        Long projectId,
        Long documentId,
        String sourceId,
        String fileName,
        Long fileSize,
        String contentType,
        String sha256,
        String url,
        LocalDateTime createdAt) {
}
