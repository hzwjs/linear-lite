package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentRevisionSummary(
        Long revisionId,
        Long sourceVersion,
        String title,
        Long editorId,
        String editorName,
        LocalDateTime createdAt) {
}
