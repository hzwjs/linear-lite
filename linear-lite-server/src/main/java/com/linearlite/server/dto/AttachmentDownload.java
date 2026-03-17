package com.linearlite.server.dto;

/**
 * 附件下载内容：用于后端代理下载并设置正确的 Content-Disposition 文件名。
 */
public record AttachmentDownload(byte[] content, String fileName, String contentType) {
    public String contentTypeOrDefault() {
        return contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream";
    }
}
