package com.linearlite.server.dto;

/**
 * PUT /api/projects/{id} 请求体。仅提交需更新的字段。
 */
public class UpdateProjectRequest {

    private String name;
    private String identifier;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }
}
