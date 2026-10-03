package com.linearlite.server.dto;

public record CreateProjectDocumentRequest(
        Long parentDocumentId,
        String title,
        String content,
        String externalSource,
        String externalSourceId, String contentFormat) {
}
