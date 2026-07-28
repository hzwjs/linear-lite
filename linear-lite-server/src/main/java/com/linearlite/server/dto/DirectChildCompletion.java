package com.linearlite.server.dto;

public class DirectChildCompletion {
    private Long totalCount;
    private Long terminalCount;

    public Long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Long totalCount) {
        this.totalCount = totalCount;
    }

    public Long getTerminalCount() {
        return terminalCount;
    }

    public void setTerminalCount(Long terminalCount) {
        this.terminalCount = terminalCount;
    }
}
