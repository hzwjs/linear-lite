package com.linearlite.server.dto;

/** 负责人在同一个本地 Pi 执行上下文中提交的一轮补充指令。 */
public class LocalPiTurnRequest {
    private String executionId;
    private String prompt;

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
