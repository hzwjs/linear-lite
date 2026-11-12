package com.linearlite.server.dto;

/** 网页显示的新增目标及其授权结果。 */
public record GoalAuthorizationRequest(String batchId, String requestOperationId,
                                       String goal, String impact, String authorizedBatchId) { }
