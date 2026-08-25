package com.linearlite.server.dto;

public class PiSettingsRequest {
    private String action;
    private String provider;
    private String modelId;
    private String level;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
}
