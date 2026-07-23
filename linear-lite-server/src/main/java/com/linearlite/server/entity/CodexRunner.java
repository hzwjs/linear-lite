package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("codex_runners")
public class CodexRunner {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId; private String name; private String tokenHash; private String status;
    private LocalDateTime lastSeenAt; private LocalDateTime createdAt; private LocalDateTime revokedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public String getName(){return name;} public void setName(String v){name=v;} public String getTokenHash(){return tokenHash;} public void setTokenHash(String v){tokenHash=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getLastSeenAt(){return lastSeenAt;} public void setLastSeenAt(LocalDateTime v){lastSeenAt=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getRevokedAt(){return revokedAt;} public void setRevokedAt(LocalDateTime v){revokedAt=v;}
}
