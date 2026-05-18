package com.linearlite.server.dto;

import com.linearlite.server.entity.Task;

import java.time.LocalDateTime;
import java.util.List;

public class TaskListItemResponse {
    private Long id;
    private String taskKey;
    private String title;
    private String status;
    private String priority;
    private Long projectId;
    private Long parentId;
    private Long creatorId;
    private Long assigneeId;
    private String assigneeDisplayName;
    private LocalDateTime dueDate;
    private LocalDateTime plannedStartDate;
    private Integer progressPercent;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer subIssueCount;
    private Integer completedSubIssueCount;
    private Boolean favorited;
    private List<TaskLabelResponse> labels;

    public static TaskListItemResponse fromTask(Task task) {
        TaskListItemResponse item = new TaskListItemResponse();
        item.setId(task.getId());
        item.setTaskKey(task.getTaskKey());
        item.setTitle(task.getTitle());
        item.setStatus(task.getStatus());
        item.setPriority(task.getPriority());
        item.setProjectId(task.getProjectId());
        item.setParentId(task.getParentId());
        item.setCreatorId(task.getCreatorId());
        item.setAssigneeId(task.getAssigneeId());
        item.setAssigneeDisplayName(task.getAssigneeDisplayName());
        item.setDueDate(task.getDueDate());
        item.setPlannedStartDate(task.getPlannedStartDate());
        item.setProgressPercent(task.getProgressPercent());
        item.setCompletedAt(task.getCompletedAt());
        item.setCreatedAt(task.getCreatedAt());
        item.setUpdatedAt(task.getUpdatedAt());
        item.setSubIssueCount(task.getSubIssueCount());
        item.setCompletedSubIssueCount(task.getCompletedSubIssueCount());
        item.setFavorited(task.getFavorited());
        item.setLabels(task.getLabels());
        return item;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public void setTaskKey(String taskKey) {
        this.taskKey = taskKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public String getAssigneeDisplayName() {
        return assigneeDisplayName;
    }

    public void setAssigneeDisplayName(String assigneeDisplayName) {
        this.assigneeDisplayName = assigneeDisplayName;
    }

    public LocalDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDateTime dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDateTime getPlannedStartDate() {
        return plannedStartDate;
    }

    public void setPlannedStartDate(LocalDateTime plannedStartDate) {
        this.plannedStartDate = plannedStartDate;
    }

    public Integer getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(Integer progressPercent) {
        this.progressPercent = progressPercent;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getSubIssueCount() {
        return subIssueCount;
    }

    public void setSubIssueCount(Integer subIssueCount) {
        this.subIssueCount = subIssueCount;
    }

    public Integer getCompletedSubIssueCount() {
        return completedSubIssueCount;
    }

    public void setCompletedSubIssueCount(Integer completedSubIssueCount) {
        this.completedSubIssueCount = completedSubIssueCount;
    }

    public Boolean getFavorited() {
        return favorited;
    }

    public void setFavorited(Boolean favorited) {
        this.favorited = favorited;
    }

    public List<TaskLabelResponse> getLabels() {
        return labels;
    }

    public void setLabels(List<TaskLabelResponse> labels) {
        this.labels = labels;
    }
}
