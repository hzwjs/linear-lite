package com.linearlite.server.mcp;

/** MCP 文档图片输入：新上传内容或已有附件身份，两者互斥。 */
record McpDocumentImageInput(
        String ref,
        String fileName,
        String contentType,
        byte[] content,
        Long assetId) {
    boolean isUpload() {
        return content != null;
    }
}
