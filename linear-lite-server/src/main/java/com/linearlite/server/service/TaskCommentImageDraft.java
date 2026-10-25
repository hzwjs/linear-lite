package com.linearlite.server.service;

/** Validated image content prepared for an atomic comment-and-binding write. */
public record TaskCommentImageDraft(
        String ref,
        String fileName,
        String contentType,
        long fileSize,
        String objectKey,
        Long sourceAttachmentId) {
}
