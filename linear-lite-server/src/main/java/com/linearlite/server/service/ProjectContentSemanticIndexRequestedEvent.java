package com.linearlite.server.service;

public record ProjectContentSemanticIndexRequestedEvent(ProjectContentType contentType, Long resourceId) {
}
