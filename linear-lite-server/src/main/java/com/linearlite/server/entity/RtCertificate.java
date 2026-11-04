package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 证书签发记录，id 即证书序列号。 */
@TableName("rt_certificates")
public class RtCertificate {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long caRef;
    private Long userId;
    private Long targetRef;
    private Long profileRef;
    private String principal;
    private String keyFingerprint;
    private LocalDateTime validAfter;
    private LocalDateTime validBefore;
    private String clientIp;
    private String oauthClientId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getCaRef() { return caRef; }
    public void setCaRef(Long caRef) { this.caRef = caRef; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTargetRef() { return targetRef; }
    public void setTargetRef(Long targetRef) { this.targetRef = targetRef; }
    public Long getProfileRef() { return profileRef; }
    public void setProfileRef(Long profileRef) { this.profileRef = profileRef; }
    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }
    public String getKeyFingerprint() { return keyFingerprint; }
    public void setKeyFingerprint(String keyFingerprint) { this.keyFingerprint = keyFingerprint; }
    public LocalDateTime getValidAfter() { return validAfter; }
    public void setValidAfter(LocalDateTime validAfter) { this.validAfter = validAfter; }
    public LocalDateTime getValidBefore() { return validBefore; }
    public void setValidBefore(LocalDateTime validBefore) { this.validBefore = validBefore; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getOauthClientId() { return oauthClientId; }
    public void setOauthClientId(String oauthClientId) { this.oauthClientId = oauthClientId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
