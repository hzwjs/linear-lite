package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 项目 SSH CA；私钥种子经加密后入库。 */
@TableName("rt_ca_keys")
public class RtCaKey {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String publicKey;
    private byte[] privateKeyEnc;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public byte[] getPrivateKeyEnc() { return privateKeyEnc; }
    public void setPrivateKeyEnc(byte[] privateKeyEnc) { this.privateKeyEnc = privateKeyEnc; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
