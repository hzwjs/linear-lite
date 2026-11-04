package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 目标上的登录身份。 */
@TableName("rt_profiles")
public class RtProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long targetRef;
    private String profileId;
    private String loginUser;
    private Integer maxTtlSeconds;
    private Boolean permitPty;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTargetRef() { return targetRef; }
    public void setTargetRef(Long targetRef) { this.targetRef = targetRef; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }
    public String getLoginUser() { return loginUser; }
    public void setLoginUser(String loginUser) { this.loginUser = loginUser; }
    public Integer getMaxTtlSeconds() { return maxTtlSeconds; }
    public void setMaxTtlSeconds(Integer maxTtlSeconds) { this.maxTtlSeconds = maxTtlSeconds; }
    public Boolean getPermitPty() { return permitPty; }
    public void setPermitPty(Boolean permitPty) { this.permitPty = permitPty; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
