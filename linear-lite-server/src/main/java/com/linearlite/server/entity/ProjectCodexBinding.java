package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("project_codex_bindings")
public class ProjectCodexBinding {
    @TableId(type = IdType.AUTO) private Long id;
    private Long projectId; private Long runnerId; private Long repositoryId; private String baseBranch; private Long createdBy; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getProjectId(){return projectId;} public void setProjectId(Long v){projectId=v;} public Long getRunnerId(){return runnerId;} public void setRunnerId(Long v){runnerId=v;} public Long getRepositoryId(){return repositoryId;} public void setRepositoryId(Long v){repositoryId=v;} public String getBaseBranch(){return baseBranch;} public void setBaseBranch(String v){baseBranch=v;} public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
