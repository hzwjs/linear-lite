package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("project_document_collab_state")
public class ProjectDocumentCollabState {
    @TableId
    private Long documentId;
    private String epoch;
    private Long commitSeq;
    private byte[] stateBinary;
    private String transitionId;
    private LocalDateTime updatedAt;

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getEpoch() { return epoch; }
    public void setEpoch(String epoch) { this.epoch = epoch; }
    public Long getCommitSeq() { return commitSeq; }
    public void setCommitSeq(Long commitSeq) { this.commitSeq = commitSeq; }
    public byte[] getStateBinary() { return stateBinary; }
    public void setStateBinary(byte[] stateBinary) { this.stateBinary = stateBinary; }
    public String getTransitionId() { return transitionId; }
    public void setTransitionId(String transitionId) { this.transitionId = transitionId; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
