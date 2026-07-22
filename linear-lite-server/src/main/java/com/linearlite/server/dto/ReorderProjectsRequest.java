package com.linearlite.server.dto;

import java.util.List;

/**
 * PUT /api/projects/order 请求体。
 */
public class ReorderProjectsRequest {

    private List<Long> projectIds;

    public List<Long> getProjectIds() {
        return projectIds;
    }

    public void setProjectIds(List<Long> projectIds) {
        this.projectIds = projectIds;
    }
}
