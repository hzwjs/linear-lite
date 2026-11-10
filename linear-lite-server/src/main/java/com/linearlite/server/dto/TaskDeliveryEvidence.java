package com.linearlite.server.dto;

import java.util.List;

/** 执行结果由宿主提交；来源版本从当前任务和执行尝试读取。 */
public record TaskDeliveryEvidence(List<CompletionCondition> conditions, List<String> changedPaths,
                                   List<String> artifactRefs, String projectCheckpoint) {
    public record CompletionCondition(String condition, String result, List<String> evidenceRefs) { }
}
