package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_documents")
public class ProjectDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long parentDocumentId;
    private String externalSource;
    private String externalSourceId;
    private String title;
    private String contentJson;
    private Integer sortOrder;
    private Long version;
    private Long creatorId;
    private Long lastEditorId;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getParentDocumentId() { return parentDocumentId; }
    public void setParentDocumentId(Long parentDocumentId) { this.parentDocumentId = parentDocumentId; }
    public String getExternalSource() { return externalSource; }
    public void setExternalSource(String externalSource) { this.externalSource = externalSource; }
    public String getExternalSourceId() { return externalSourceId; }
    public void setExternalSourceId(String externalSourceId) { this.externalSourceId = externalSourceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Long getLastEditorId() { return lastEditorId; }
    public void setLastEditorId(Long lastEditorId) { this.lastEditorId = lastEditorId; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
