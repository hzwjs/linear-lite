package com.linearlite.server.dto;

import java.util.ArrayList;
import java.util.List;

public class TaskImportResponse {

    private int createdCount;
    private int parentCount;
    private int subtaskCount;
    private List<String> taskKeys = new ArrayList<>();

    public int getCreatedCount() {
        return createdCount;
    }

    public void setCreatedCount(int createdCount) {
        this.createdCount = createdCount;
    }

    public int getParentCount() {
        return parentCount;
    }

    public void setParentCount(int parentCount) {
        this.parentCount = parentCount;
    }

    public int getSubtaskCount() {
        return subtaskCount;
    }

    public void setSubtaskCount(int subtaskCount) {
        this.subtaskCount = subtaskCount;
    }

    public List<String> getTaskKeys() {
        return taskKeys;
    }

    public void setTaskKeys(List<String> taskKeys) {
        this.taskKeys = taskKeys;
    }
}
