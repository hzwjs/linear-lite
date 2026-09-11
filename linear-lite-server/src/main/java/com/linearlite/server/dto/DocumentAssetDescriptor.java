package com.linearlite.server.dto;

/**
 * 文档图片资源描述：定位对象键并给出响应头所需的元数据。
 */
public record DocumentAssetDescriptor(
        String objectKey,
        String contentType,
        long contentLength,
        String etag) {

    public String contentTypeOrDefault() {
        return contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream";
    }
}
