package com.linearlite.server.dto;

import java.util.List;

/** Agent 在自己的任务下拆出的子任务；只含需求，不含依赖或文件清单。 */
public record TaskSplitRequest(String reason, List<Subtask> subtasks) {
    public record Subtask(String title, String description) { }
}
