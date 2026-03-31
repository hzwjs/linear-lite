package com.linearlite.server.dto;

/**
 * 统计查询参数。
 */
public class AnalyticsQuery {

    private Long projectId;
    private String granularity; // day | week | month | year
    private String from;        // ISO 日期时间
    private String to;          // ISO 日期时间
    /** 任务明细口径：created | completed | due | all；空/all/created 均按 created_at 筛 */
    private String taskListScope;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getTaskListScope() {
        return taskListScope;
    }

    public void setTaskListScope(String taskListScope) {
        this.taskListScope = taskListScope;
    }
}
