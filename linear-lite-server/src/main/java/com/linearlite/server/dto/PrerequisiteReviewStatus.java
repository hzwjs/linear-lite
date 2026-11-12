package com.linearlite.server.dto;

/** 一份前置交付的采用待办与已选材料的当前核验状态。 */
public record PrerequisiteReviewStatus(Long sourceTaskId, Long sourceAttemptId, Long sourceInputRevision,
                                       boolean adoptionRequired, String materialStatus, String reason) { }
