package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.mapper.StringListJsonTypeHandler;
import java.time.LocalDateTime;
import java.util.List;

@TableName(value = "task_execution_attempts", autoResultMap = true)
public class TaskExecutionAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long bindingId;
    private String batchId;
    private String hostInstanceId;
    private String agentInstanceId;
    @JsonIgnore
    private String claimTokenHash;
    private Long inputRevision;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode prerequisiteVersions;
    private String status;
    private LocalDateTime leaseUntil;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode checkpoints;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode resultEvidence;
    private String stopReason;
    private LocalDateTime stopRequestedAt;
    private LocalDateTime stopConfirmedAt;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode stopConfirmation;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getHostInstanceId() { return hostInstanceId; }
    public void setHostInstanceId(String hostInstanceId) { this.hostInstanceId = hostInstanceId; }
    public String getAgentInstanceId() { return agentInstanceId; }
    public void setAgentInstanceId(String agentInstanceId) { this.agentInstanceId = agentInstanceId; }
    public String getClaimTokenHash() { return claimTokenHash; }
    public void setClaimTokenHash(String claimTokenHash) { this.claimTokenHash = claimTokenHash; }
    public Long getInputRevision() { return inputRevision; }
    public void setInputRevision(Long inputRevision) { this.inputRevision = inputRevision; }
    public JsonNode getPrerequisiteVersions() { return prerequisiteVersions; }
    public void setPrerequisiteVersions(JsonNode prerequisiteVersions) { this.prerequisiteVersions = prerequisiteVersions; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public JsonNode getCheckpoints() { return checkpoints; }
    public void setCheckpoints(JsonNode checkpoints) { this.checkpoints = checkpoints; }
    public JsonNode getResultEvidence() { return resultEvidence; }
    public void setResultEvidence(JsonNode resultEvidence) { this.resultEvidence = resultEvidence; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public LocalDateTime getStopRequestedAt() { return stopRequestedAt; }
    public void setStopRequestedAt(LocalDateTime stopRequestedAt) { this.stopRequestedAt = stopRequestedAt; }
    public LocalDateTime getStopConfirmedAt() { return stopConfirmedAt; }
    public void setStopConfirmedAt(LocalDateTime stopConfirmedAt) { this.stopConfirmedAt = stopConfirmedAt; }
    public JsonNode getStopConfirmation() { return stopConfirmation; }
    public void setStopConfirmation(JsonNode stopConfirmation) { this.stopConfirmation = stopConfirmation; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
