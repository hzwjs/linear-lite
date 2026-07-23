package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("project_email_dispatches")
public class ProjectEmailDispatch {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String scenarioKey;
    private LocalDate businessDate;
    private Long recipientUserId;
    private String status;
    private String subject;
    private Integer taskCount;
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getScenarioKey() { return scenarioKey; }
    public void setScenarioKey(String scenarioKey) { this.scenarioKey = scenarioKey; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public Long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(Long recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public Integer getTaskCount() { return taskCount; }
    public void setTaskCount(Integer taskCount) { this.taskCount = taskCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
