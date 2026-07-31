package com.linearlite.server.service;

import java.util.List;

/** 在业务事务内请求删除一组项目内容索引。 */
public record ProjectContentSemanticDeleteRequestedEvent(ProjectContentType contentType, List<Long> resourceIds) {
    public ProjectContentSemanticDeleteRequestedEvent {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
    }
}
