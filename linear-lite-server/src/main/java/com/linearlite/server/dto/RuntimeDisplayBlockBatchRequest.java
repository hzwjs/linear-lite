package com.linearlite.server.dto;

import java.util.List;

public class RuntimeDisplayBlockBatchRequest {
    private String executionId;
    private List<RuntimeDisplayBlockRequest> blocks;

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public List<RuntimeDisplayBlockRequest> getBlocks() { return blocks; }
    public void setBlocks(List<RuntimeDisplayBlockRequest> blocks) { this.blocks = blocks; }
}
