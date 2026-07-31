package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 项目接收 GitLab Push Webhook 的唯一仓库身份。 */
@TableName("project_gitlab_repositories")
public class ProjectGitLabRepository {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String repositoryUrl;
    private String repositoryPath;
    private String webhookTokenHash;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
    public String getRepositoryPath() { return repositoryPath; }
    public void setRepositoryPath(String repositoryPath) { this.repositoryPath = repositoryPath; }
    public String getWebhookTokenHash() { return webhookTokenHash; }
    public void setWebhookTokenHash(String webhookTokenHash) { this.webhookTokenHash = webhookTokenHash; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
