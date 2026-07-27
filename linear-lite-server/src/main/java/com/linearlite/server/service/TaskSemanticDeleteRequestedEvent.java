package com.linearlite.server.service;

import java.util.List;

public record TaskSemanticDeleteRequestedEvent(List<Long> taskIds) {
    public TaskSemanticDeleteRequestedEvent {
        taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
    }
}
