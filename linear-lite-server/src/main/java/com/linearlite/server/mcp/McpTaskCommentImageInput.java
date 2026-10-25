package com.linearlite.server.mcp;

public record McpTaskCommentImageInput(String ref, String fileName, String contentType, byte[] content, Long attachmentId) {
    public boolean isUpload() { return content != null; }
}
