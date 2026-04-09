package com.linearlite.server.dto;

import java.util.ArrayList;
import java.util.List;

public class CreateTaskCommentRequest {

    private String body;
    private List<Long> mentionedUserIds = new ArrayList<>();

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<Long> getMentionedUserIds() {
        return mentionedUserIds;
    }

    public void setMentionedUserIds(List<Long> mentionedUserIds) {
        this.mentionedUserIds = mentionedUserIds != null ? mentionedUserIds : new ArrayList<>();
    }
}
