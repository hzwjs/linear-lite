package com.linearlite.server.dto;

/** Agent 依据发现的成果维护当前任务的关系。 */
public record DependencyChange(String action, Long prerequisiteTaskId, String missing, String reason,
                               String evidence, String goal, String impact) { }
