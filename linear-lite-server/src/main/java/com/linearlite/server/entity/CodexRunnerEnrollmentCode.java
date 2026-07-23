package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("codex_runner_enrollment_codes")
public class CodexRunnerEnrollmentCode {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId; private String codeHash; private LocalDateTime expiresAt; private LocalDateTime consumedAt; private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public String getCodeHash(){return codeHash;} public void setCodeHash(String v){codeHash=v;} public LocalDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(LocalDateTime v){expiresAt=v;}
    public LocalDateTime getConsumedAt(){return consumedAt;} public void setConsumedAt(LocalDateTime v){consumedAt=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
