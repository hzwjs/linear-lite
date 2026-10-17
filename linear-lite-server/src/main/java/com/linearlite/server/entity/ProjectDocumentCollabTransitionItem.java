package com.linearlite.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("project_document_collab_transition_items")
public class ProjectDocumentCollabTransitionItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String transitionId;
    private Long documentId;
    private String epoch;
    private String nextEpoch;
    private Long commitSeq;
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTransitionId() { return transitionId; }
    public void setTransitionId(String transitionId) { this.transitionId = transitionId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getEpoch() { return epoch; }
    public void setEpoch(String epoch) { this.epoch = epoch; }
    public String getNextEpoch() { return nextEpoch; }
    public void setNextEpoch(String nextEpoch) { this.nextEpoch = nextEpoch; }
    public Long getCommitSeq() { return commitSeq; }
    public void setCommitSeq(Long commitSeq) { this.commitSeq = commitSeq; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
