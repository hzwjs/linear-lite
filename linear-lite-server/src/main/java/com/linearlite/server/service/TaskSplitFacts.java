package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.entity.TaskExecutionAttempt;
import java.util.List;

/** 拆分事实：Agent 在自己的尝试里把任务拆成子任务，停止确认后父任务由子任务汇总完成。 */
final class TaskSplitFacts {
    static final String KIND = "task_split";

    private TaskSplitFacts() { }

    static boolean confirmed(List<TaskExecutionAttempt> history) {
        return history.stream().anyMatch(attempt -> attempt.getStopConfirmedAt() != null
                && attempt.getCheckpoints() != null && attempt.getCheckpoints().isArray()
                && contains(attempt.getCheckpoints()));
    }

    private static boolean contains(JsonNode checkpoints) {
        for (JsonNode fact : checkpoints) if (KIND.equals(fact.path("kind").asText())) return true;
        return false;
    }
}
