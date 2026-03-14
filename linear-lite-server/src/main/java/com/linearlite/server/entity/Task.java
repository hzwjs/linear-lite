package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("tasks")
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 对外展示的任务 ID，格式：项目 identifier + '-' + 项目内序号，如 ENG-1 */
    private String taskKey;
    private String title;
    private String description;
    private String status;
    private String priority;
    private Long projectId;
    /** 父任务 ID，null 表示顶层任务 */
    private Long parentId;
    private Long creatorId;
    private Long assigneeId;
    private LocalDateTime dueDate;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 直接子任务数量，仅响应时由后端填充，不持久化 */
    @TableField(exist = false)
    private Integer subIssueCount;
    /** 已完成的直接子任务数量，仅响应时由后端填充，不持久化 */
    @TableField(exist = false)
    private Integer completedSubIssueCount;

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

    public LocalDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDateTime dueDate) {
        this.dueDate = dueDate;
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
}
