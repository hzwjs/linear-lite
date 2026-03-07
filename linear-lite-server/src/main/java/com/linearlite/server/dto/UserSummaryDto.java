package com.linearlite.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户摘要，供前端 Assignee 选择：id、username、avatar_url。
 */
public class UserSummaryDto {

    private Long id;
    private String username;
    @JsonProperty("avatar_url")
    private String avatarUrl;

    public UserSummaryDto() {
    }

    public UserSummaryDto(Long id, String username, String avatarUrl) {
        this.id = id;
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }
}
