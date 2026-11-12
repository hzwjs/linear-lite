package com.linearlite.server.dto;

import java.util.List;

/** 任务输入版本与当前前置事实，执行授权归批次集合。 */
public record TaskCollaborationInputs(Long inputRevision, List<Long> prerequisiteTaskIds) { }
