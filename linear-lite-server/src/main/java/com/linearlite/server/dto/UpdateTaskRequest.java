package com.linearlite.server.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PUT /api/tasks/{id} 请求体。仅提交需更新的字段。completedAt 由后端根据状态自动维护，请求中忽略。
 */
public class UpdateTaskRequest {

    private String title;
    /** 父任务 ID，可选；传具体 ID 时设为该父任务 */
    private Long parentId;
    /** 为 true 时解除父子关系（将 parent_id 置为 null）；与 parentId 二选一 */
    private Boolean clearParent;
    private String description;
    private String status;
    private String priority;
    /** 指派人 ID；与 clearAssignee 二选一：传具体 ID 时设为该用户 */
    private Long assigneeId;
    /** 为 true 时清空指派人（将 assignee_id 置为 null） */
    private Boolean clearAssignee;
    private LocalDateTime dueDate;
    /** 为 true 时清空截止日期 */
    private Boolean clearDueDate;
    private LocalDateTime plannedStartDate;
    /** 为 true 时清空计划开始日期 */
    private Boolean clearPlannedStart;
    /** 完成进度 0–100 */
    private Integer progressPercent;
    /** 可选；有值时整包替换任务标签 */
    private List<TaskLabelItemRequest> labels;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Boolean getClearParent() {
        return clearParent;
    }

    public void setClearParent(Boolean clearParent) {
        this.clearParent = clearParent;
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

    public Boolean getClearAssignee() {
        return clearAssignee;
    }

    public void setClearAssignee(Boolean clearAssignee) {
        this.clearAssignee = clearAssignee;
    }

    public LocalDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDateTime dueDate) {
        this.dueDate = dueDate;
    }

    public Boolean getClearDueDate() {
        return clearDueDate;
    }

    public void setClearDueDate(Boolean clearDueDate) {
        this.clearDueDate = clearDueDate;
    }

    public LocalDateTime getPlannedStartDate() {
        return plannedStartDate;
    }

    public void setPlannedStartDate(LocalDateTime plannedStartDate) {
        this.plannedStartDate = plannedStartDate;
    }

    public Boolean getClearPlannedStart() {
        return clearPlannedStart;
    }

    public void setClearPlannedStart(Boolean clearPlannedStart) {
        this.clearPlannedStart = clearPlannedStart;
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
