package com.linearlite.server.dto;

import java.util.ArrayList;
import java.util.List;

public class CreateTaskCommentRequest {

    private String body;
    private Long parentId;
    private Long rootId;
    private int depth;
    private List<Long> mentionedUserIds = new ArrayList<>();

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Long getRootId() {
        return rootId;
    }

    public void setRootId(Long rootId) {
        this.rootId = rootId;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public List<Long> getMentionedUserIds() {
        return mentionedUserIds;
    }

    public void setMentionedUserIds(List<Long> mentionedUserIds) {
        this.mentionedUserIds = mentionedUserIds != null ? mentionedUserIds : new ArrayList<>();
    }
}
