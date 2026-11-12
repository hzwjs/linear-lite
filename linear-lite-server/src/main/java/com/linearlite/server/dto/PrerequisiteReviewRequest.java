package com.linearlite.server.dto;

import java.util.List;

/** 复核绑定前置交付及宿主读取事实。 */
public record PrerequisiteReviewRequest(Long sourceTaskId, Long sourceAttemptId, Long sourceInputRevision,
                                        String decision, String finding, String evidence,
                                        List<ArtifactObservation> observations, String goal, String impact) { }
