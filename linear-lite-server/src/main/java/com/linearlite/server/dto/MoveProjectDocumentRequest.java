package com.linearlite.server.dto;

public record MoveProjectDocumentRequest(Long parentDocumentId, Long previousSiblingId) {
}
