package com.linearlite.server.dto;

import java.util.List;

/**
 * 统计聚合响应：趋势 + 项目全局快照 + 各维度分布。
 */
public class AnalyticsSummaryResponse {

    private Meta meta;
    private List<TrendBucket> trend;
    private ProjectSnapshot currentSnapshot;
    private List<StatusCount> statusBreakdown;
    private List<AssigneeCount> assigneeBreakdown;
    private List<PriorityCount> priorityBreakdown;

    public static class Meta {
        private Long projectId;
        private String timezone;
        private String bucketUnit;
        private String weekStartDay;

        public Meta() {
        }

        public Meta(Long projectId, String timezone, String bucketUnit, String weekStartDay) {
            this.projectId = projectId;
            this.timezone = timezone;
            this.bucketUnit = bucketUnit;
            this.weekStartDay = weekStartDay;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public String getBucketUnit() {
            return bucketUnit;
        }

        public void setBucketUnit(String bucketUnit) {
            this.bucketUnit = bucketUnit;
        }

        public String getWeekStartDay() {
            return weekStartDay;
        }

        public void setWeekStartDay(String weekStartDay) {
            this.weekStartDay = weekStartDay;
        }
    }

    public Meta getMeta() {
        return meta;
    }

    public void setMeta(Meta meta) {
        this.meta = meta;
    }

    public List<TrendBucket> getTrend() {
        return trend;
    }

    public void setTrend(List<TrendBucket> trend) {
        this.trend = trend;
    }

    public ProjectSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    public void setCurrentSnapshot(ProjectSnapshot currentSnapshot) {
        this.currentSnapshot = currentSnapshot;
    }

    public List<StatusCount> getStatusBreakdown() {
        return statusBreakdown;
    }

    public void setStatusBreakdown(List<StatusCount> statusBreakdown) {
        this.statusBreakdown = statusBreakdown;
    }

    public List<AssigneeCount> getAssigneeBreakdown() {
        return assigneeBreakdown;
    }

    public void setAssigneeBreakdown(List<AssigneeCount> assigneeBreakdown) {
        this.assigneeBreakdown = assigneeBreakdown;
    }

    public List<PriorityCount> getPriorityBreakdown() {
        return priorityBreakdown;
    }

    public void setPriorityBreakdown(List<PriorityCount> priorityBreakdown) {
        this.priorityBreakdown = priorityBreakdown;
    }
}
