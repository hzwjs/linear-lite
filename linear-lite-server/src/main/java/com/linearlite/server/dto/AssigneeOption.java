package com.linearlite.server.dto;

public class AssigneeOption {
    private String key;
    private String name;
    private String avatarUrl;
    private String principalType;

    public AssigneeOption(String key, String name, String avatarUrl, String principalType) {
        this.key = key;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.principalType = principalType;
    }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getPrincipalType() { return principalType; }
}
