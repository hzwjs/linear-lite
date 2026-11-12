package com.linearlite.server.dto;

import com.linearlite.server.service.TaskPrerequisiteReviewService.ReviewFact;
import java.util.List;

/** 复核事实按尝试投影，原文来源为执行检查点。 */
public record AttemptPrerequisiteReviews(Long attemptId, List<ReviewFact> reviews) { }
