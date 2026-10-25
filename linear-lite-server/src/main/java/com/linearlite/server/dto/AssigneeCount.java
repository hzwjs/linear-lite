package com.linearlite.server.dto;

public class AssigneeCount {

    private String assigneeKey;
    private String assigneeName;
    private int totalCount;
    private int completedCount;
    private int inProgressCount;

    public AssigneeCount() {
    }

    public AssigneeCount(String assigneeKey, String assigneeName, int totalCount, int completedCount, int inProgressCount) {
        this.assigneeKey = assigneeKey;
        this.assigneeName = assigneeName;
        this.totalCount = totalCount;
        this.completedCount = completedCount;
        this.inProgressCount = inProgressCount;
    }

    public String getAssigneeKey() {
        return assigneeKey;
    }

    public void setAssigneeKey(String assigneeKey) {
        this.assigneeKey = assigneeKey;
    }

    public String getAssigneeName() {
        return assigneeName;
    }

    public void setAssigneeName(String assigneeName) {
        this.assigneeName = assigneeName;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(int completedCount) {
        this.completedCount = completedCount;
    }

    public int getInProgressCount() {
        return inProgressCount;
    }

    public void setInProgressCount(int inProgressCount) {
        this.inProgressCount = inProgressCount;
    }
}
