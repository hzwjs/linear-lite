package com.linearlite.server.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/tasks 请求体。creator_id 由后端从 JWT 解析并绑定。
 */
public class CreateTaskRequest {

    private Long projectId;
    /** 父任务 ID，可选；传则创建为子任务 */
    private Long parentId;
    private String title;
    private String description;
    private String status;
    private String priority;
    private Long assigneeId;
    private LocalDate dueDate;
    private LocalDate plannedStartDate;
    /** 完成进度 0–100，缺省为 0 */
    private Integer progressPercent;
    /** 可选；有值时整包替换任务标签（创建后写入） */
    private List<TaskLabelItemRequest> labels;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDate getPlannedStartDate() {
        return plannedStartDate;
    }

    public void setPlannedStartDate(LocalDate plannedStartDate) {
        this.plannedStartDate = plannedStartDate;
    }

    public Integer getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(Integer progressPercent) {
        this.progressPercent = progressPercent;
    }

    public List<TaskLabelItemRequest> getLabels() {
        return labels;
    }

    public void setLabels(List<TaskLabelItemRequest> labels) {
        this.labels = labels;
    }
}
