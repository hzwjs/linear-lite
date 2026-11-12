package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("collaboration_batches")
public class CollaborationBatch {
    @TableId(type = IdType.INPUT)
    private String batchId;
    private Long projectId;
    private Long userId;
    private Long bindingId;
    private Long bindingRevision;
    private Long hostAuthorizationId;
    private Integer maxSlots;
    private Long maxDurationMs;
    private Integer maxAttempts;
    private String desiredIntent;
    private Long intentRevision;
    private String lifecycle;
    private String hostInstanceId;
    private Long runEpoch;
    private LocalDateTime runLeaseUntil;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime endedAt;
    private String endReason;
    private LocalDateTime createdAt;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }
    public Long getBindingRevision() { return bindingRevision; }
    public void setBindingRevision(Long bindingRevision) { this.bindingRevision = bindingRevision; }
    public Long getHostAuthorizationId() { return hostAuthorizationId; }
    public void setHostAuthorizationId(Long hostAuthorizationId) { this.hostAuthorizationId = hostAuthorizationId; }
    public Integer getMaxSlots() { return maxSlots; }
    public void setMaxSlots(Integer maxSlots) { this.maxSlots = maxSlots; }
    public Long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(Long maxDurationMs) { this.maxDurationMs = maxDurationMs; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getDesiredIntent() { return desiredIntent; }
    public void setDesiredIntent(String desiredIntent) { this.desiredIntent = desiredIntent; }
    public Long getIntentRevision() { return intentRevision; }
    public void setIntentRevision(Long intentRevision) { this.intentRevision = intentRevision; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String lifecycle) { this.lifecycle = lifecycle; }
    public String getHostInstanceId() { return hostInstanceId; }
    public void setHostInstanceId(String hostInstanceId) { this.hostInstanceId = hostInstanceId; }
    public Long getRunEpoch() { return runEpoch; }
    public void setRunEpoch(Long runEpoch) { this.runEpoch = runEpoch; }
    public LocalDateTime getRunLeaseUntil() { return runLeaseUntil; }
    public void setRunLeaseUntil(LocalDateTime runLeaseUntil) { this.runLeaseUntil = runLeaseUntil; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(LocalDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String endReason) { this.endReason = endReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
