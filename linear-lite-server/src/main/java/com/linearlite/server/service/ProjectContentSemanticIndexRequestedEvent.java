package com.linearlite.server.service;

/** 在业务事务内请求写入或刷新单条项目内容索引。 */
public record ProjectContentSemanticIndexRequestedEvent(ProjectContentType contentType, Long resourceId) {
}
