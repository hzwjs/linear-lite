package com.linearlite.server.dto;

/**
 * 文档图片资源身份：正文图片节点只保存 assetId，其余渲染信息由本清单解析。
 */
public record DocumentImageAsset(
        Long assetId,
        String contentHash,
        Integer width,
        Integer height,
        String thumbnailUrl,
        String originalUrl) {
}
