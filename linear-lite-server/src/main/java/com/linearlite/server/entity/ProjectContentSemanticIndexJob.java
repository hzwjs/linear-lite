package com.linearlite.server.entity;

import java.time.LocalDateTime;

/** 任务与项目文档共用的去重索引任务。 */
public class ProjectContentSemanticIndexJob {
    private String contentType;
    private Long resourceId;
    private String operation;
    private LocalDateTime runAfter;
    private Long generation;
    private Integer attempts;
    private LocalDateTime leaseUntil;

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public LocalDateTime getRunAfter() { return runAfter; }
    public void setRunAfter(LocalDateTime runAfter) { this.runAfter = runAfter; }
    public Long getGeneration() { return generation; }
    public void setGeneration(Long generation) { this.generation = generation; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
}
