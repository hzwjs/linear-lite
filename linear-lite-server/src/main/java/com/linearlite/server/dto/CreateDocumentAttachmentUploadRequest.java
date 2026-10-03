package com.linearlite.server.dto;

public record CreateDocumentAttachmentUploadRequest(String fileName, long fileSize, String contentType) {
}
