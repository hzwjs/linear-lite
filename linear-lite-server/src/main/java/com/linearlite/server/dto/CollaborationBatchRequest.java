package com.linearlite.server.dto;

/** 批次凭据只用于认证，不进入日志。 */
public record CollaborationBatchRequest(String batchToken, Long bindingId, String deviceId, String projectDirectory) {
    @Override
    public String toString() {
        return "CollaborationBatchRequest[batchToken=<redacted>, bindingId=" + bindingId
                + ", deviceId=" + deviceId + ", projectDirectory=" + projectDirectory + "]";
    }
}
