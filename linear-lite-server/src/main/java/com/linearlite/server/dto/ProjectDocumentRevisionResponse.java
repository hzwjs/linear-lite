package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentRevisionResponse(
        Long documentId,
        Long revisionId,
        Long sourceVersion,
        String title,
        String content,
        Long editorId,
        String editorName,
        LocalDateTime createdAt) {
}
