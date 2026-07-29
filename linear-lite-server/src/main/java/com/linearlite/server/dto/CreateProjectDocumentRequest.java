package com.linearlite.server.dto;

public record CreateProjectDocumentRequest(
        Long parentDocumentId,
        String title,
        String content,
        String externalSource,
        String externalSourceId) {
    public CreateProjectDocumentRequest(Long parentDocumentId, String title) {
        this(parentDocumentId, title, null, null, null);
    }

    public CreateProjectDocumentRequest(Long parentDocumentId, String title, String content) {
        this(parentDocumentId, title, content, null, null);
    }
}
