package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("task_semantic_index_jobs")
public class TaskSemanticIndexJob {
    @TableId
    private Long taskId;
    private String operation;
    private String contentHash;
    private LocalDateTime runAfter;
    private Long version;
    private Integer attempts;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public LocalDateTime getRunAfter() { return runAfter; }
    public void setRunAfter(LocalDateTime runAfter) { this.runAfter = runAfter; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
}
