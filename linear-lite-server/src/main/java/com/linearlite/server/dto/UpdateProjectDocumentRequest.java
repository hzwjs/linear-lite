package com.linearlite.server.dto;

public record UpdateProjectDocumentRequest(Long expectedVersion, String title, String content) {
}
