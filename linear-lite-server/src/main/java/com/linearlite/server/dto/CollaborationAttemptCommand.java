package com.linearlite.server.dto;

/** 领取凭据只用于认证，不进入日志。 */
public record CollaborationAttemptCommand(Long attemptId, String claimToken, Long inputRevision,
                                          String hostInstanceId, String agentInstanceId, String operationId) {
    @Override
    public String toString() {
        return "CollaborationAttemptCommand[attemptId=" + attemptId + ", claimToken=<redacted>, inputRevision="
                + inputRevision + ", hostInstanceId=" + hostInstanceId + ", agentInstanceId=" + agentInstanceId
                + ", operationId=" + operationId + "]";
    }
}
