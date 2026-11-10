package com.linearlite.server.dto;

import java.util.List;

/** 一次任务协作输入替换；inputRevision 为调用方读取的版本。 */
public record TaskCollaborationInputs(Long inputRevision, Boolean agentExecutionEnabled,
                                      List<String> writeScopes, List<PublicContextRef> publicContextRefs,
                                      List<Long> prerequisiteTaskIds) { }
