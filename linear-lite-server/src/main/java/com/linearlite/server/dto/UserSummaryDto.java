package com.linearlite.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户摘要，供前端 Assignee 选择：身份类型也必须随摘要返回，才能区分人类与 Agent。
 */
public class UserSummaryDto {

    private Long id;
    private String username;
    @JsonProperty("avatar_url")
    private String avatarUrl;
    @JsonProperty("principal_type")
    private String principalType;
    @JsonProperty("agent_key")
    private String agentKey;

    public UserSummaryDto() {
    }

    public UserSummaryDto(Long id, String username, String avatarUrl, String principalType, String agentKey) {
        this.id = id;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.principalType = principalType;
        this.agentKey = agentKey;
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

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

}
