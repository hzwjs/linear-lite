package com.linearlite.server.dto;

import java.util.List;

/**
 * 项目全局快照：截至当前的状态全貌（不受查询区间 from/to 影响）。
 */
public class ProjectSnapshot {

    private int totalCount;
    private List<StatusCount> statusBreakdown;
    private int overdueCount;

    public ProjectSnapshot() {
    }

    public ProjectSnapshot(int totalCount, List<StatusCount> statusBreakdown, int overdueCount) {
        this.totalCount = totalCount;
        this.statusBreakdown = statusBreakdown;
        this.overdueCount = overdueCount;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public List<StatusCount> getStatusBreakdown() {
        return statusBreakdown;
    }

    public void setStatusBreakdown(List<StatusCount> statusBreakdown) {
        this.statusBreakdown = statusBreakdown;
    }

    public int getOverdueCount() {
        return overdueCount;
    }

    public void setOverdueCount(int overdueCount) {
        this.overdueCount = overdueCount;
    }
}
