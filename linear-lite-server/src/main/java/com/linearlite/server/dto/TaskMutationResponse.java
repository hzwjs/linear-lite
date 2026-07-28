package com.linearlite.server.dto;

import com.linearlite.server.entity.Task;

import java.util.List;

/** Result of one task mutation plus every ancestor state changed by that mutation. */
public record TaskMutationResponse(Task task, List<TaskStateChange> autoCompletedAncestors) {
}
