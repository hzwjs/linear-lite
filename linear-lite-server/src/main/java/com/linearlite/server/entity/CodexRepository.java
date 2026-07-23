package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("codex_repositories")
public class CodexRepository {
    @TableId(type = IdType.AUTO) private Long id;
    private Long runnerId; private String repositoryKey; private String displayName; private String remoteIdentity; private String defaultBranch;
    private LocalDateTime lastSeenAt; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getRunnerId(){return runnerId;} public void setRunnerId(Long v){runnerId=v;}
    public String getRepositoryKey(){return repositoryKey;} public void setRepositoryKey(String v){repositoryKey=v;} public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public String getRemoteIdentity(){return remoteIdentity;} public void setRemoteIdentity(String v){remoteIdentity=v;} public String getDefaultBranch(){return defaultBranch;} public void setDefaultBranch(String v){defaultBranch=v;}
    public LocalDateTime getLastSeenAt(){return lastSeenAt;} public void setLastSeenAt(LocalDateTime v){lastSeenAt=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
