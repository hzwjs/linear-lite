package com.linearlite.server.dto;

public class PiSettingsErrorRequest {
    private String executionId;
    private String errorMessage;

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
