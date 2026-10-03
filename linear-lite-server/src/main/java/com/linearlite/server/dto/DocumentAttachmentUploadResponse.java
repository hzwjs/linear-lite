package com.linearlite.server.dto;

import java.util.List;

public record DocumentAttachmentUploadResponse(
        String sessionToken, long partSize, String status,
        List<UploadedPart> parts, ProjectDocumentAttachmentResponse attachment) {
    public record UploadedPart(int partNumber, long size) { }
}
