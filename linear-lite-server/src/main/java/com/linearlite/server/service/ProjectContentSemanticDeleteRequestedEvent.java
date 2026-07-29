package com.linearlite.server.service;

import java.util.List;

public record ProjectContentSemanticDeleteRequestedEvent(ProjectContentType contentType, List<Long> resourceIds) {
    public ProjectContentSemanticDeleteRequestedEvent {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
    }
}
