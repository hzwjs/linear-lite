package com.linearlite.server.dto;

/** 人类用户取消 Pi 执行时必须携带页面当前看到的 executionId。 */
public class AgentTaskCancelRequest {
    private String executionId;

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
}
