package com.linearlite.server.dto;

import java.time.LocalDateTime;

/**
 * 任务 API 响应。id 为带项目前缀的 task_key（如 ENG-1），供前端与 PUT 路径使用。
 */
public class TaskResponse {

    private String id;          // task_key
    private String title;
    private String description;
    private String status;
    private String priority;
    private Long projectId;
    private Long parentId;
    private Long creatorId;
    private Long assigneeId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer subIssueCount;
    private Integer completedSubIssueCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
}
