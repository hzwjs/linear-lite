package com.linearlite.server.dto;

import java.util.ArrayList;
import java.util.List;

public class TaskImportRequest {

    private Long projectId;
    private List<TaskImportRowRequest> rows = new ArrayList<>();

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public List<TaskImportRowRequest> getRows() {
        return rows;
    }

    public void setRows(List<TaskImportRowRequest> rows) {
        this.rows = rows;
    }
}
