package com.linearlite.server.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectDocumentRevisionResponse(
        Long documentId,
        Long revisionId,
        Long sourceVersion,
        String title,
        String content,
        List<DocumentImageAsset> imageAssets,
        Long editorId,
        String editorName,
        LocalDateTime createdAt) {
    // editor_id 无外键约束，被删除用户对应的 editorName 可能为 null，前端需处理
}
