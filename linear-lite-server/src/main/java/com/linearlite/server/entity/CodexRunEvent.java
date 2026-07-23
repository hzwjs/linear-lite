package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
@TableName("codex_run_events") public class CodexRunEvent {
 @TableId(type=IdType.AUTO) private Long id; private String runId; private Long sequenceNo; private String eventType; private String eventPayload; private LocalDateTime createdAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getRunId(){return runId;} public void setRunId(String v){runId=v;} public Long getSequenceNo(){return sequenceNo;} public void setSequenceNo(Long v){sequenceNo=v;} public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;} public String getEventPayload(){return eventPayload;} public void setEventPayload(String v){eventPayload=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
