package com.linearlite.server.service;

import java.util.List;

/** 文档版本或可用性变更，在持项目锁的文档事务中传递。 */
public record CollaborationDocumentChangedEvent(Long projectId, List<Change> changes, Long actorUserId) {
    public record Change(Long documentId, Long version, boolean available) { }
}
