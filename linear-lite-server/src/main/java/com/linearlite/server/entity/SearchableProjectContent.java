package com.linearlite.server.entity;

import java.time.LocalDateTime;

/** 搜索索引的统一输入模型；sourceContent 仅用于提取可见文本，不直接返回给客户端。 */
public class SearchableProjectContent {
    private String contentType;
    private Long numericId;
    private String resourceId;
    private Long projectId;
    private String title;
    private String sourceContent;
    private LocalDateTime sourceUpdatedAt;

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getNumericId() { return numericId; }
    public void setNumericId(Long numericId) { this.numericId = numericId; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }
    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(LocalDateTime sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }
}
