package com.linearlite.server.dto;

/**
 * POST /api/projects 请求体。
 */
public class CreateProjectRequest {

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
