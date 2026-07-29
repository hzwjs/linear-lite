package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentRevisionResponse(
        Long documentId,
        Long version,
        String title,
        String content,
        Long editorId,
        LocalDateTime createdAt) {
}
