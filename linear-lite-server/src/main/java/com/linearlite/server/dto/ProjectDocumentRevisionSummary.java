package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentRevisionSummary(
        Long version,
        String title,
        Long editorId,
        LocalDateTime createdAt) {
}
