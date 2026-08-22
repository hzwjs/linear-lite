package com.linearlite.server.dto;

import java.time.LocalDateTime;

public record ProjectDocumentRevisionSummary(
        Long revisionId,
        Long sourceVersion,
        String title,
        Long editorId,
        String editorName,
        LocalDateTime createdAt) {
    // editor_id 无外键约束，被删除用户对应的 editorName 可能为 null，由调用方决定展示
}
