package com.linearlite.server.entity;

import java.time.LocalDateTime;

/** 任务与项目文档共用的去重索引任务。 */
public class ProjectContentSemanticIndexJob {
    private String contentType;
    private Long resourceId;
    private String operation;
    private String contentHash;
    private LocalDateTime runAfter;
    private Long version;
    private Integer attempts;

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
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
