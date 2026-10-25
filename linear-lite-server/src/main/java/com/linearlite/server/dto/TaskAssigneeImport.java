package com.linearlite.server.dto;

public class TaskAssigneeImport {
    private String kind;
    private String key;
    private String displayName;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
