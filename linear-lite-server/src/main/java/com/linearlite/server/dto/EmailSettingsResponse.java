package com.linearlite.server.dto;

public class EmailSettingsResponse {
    private String scenarioKey;
    private Boolean enabled;

    public EmailSettingsResponse(String scenarioKey, Boolean enabled) {
        this.scenarioKey = scenarioKey;
        this.enabled = enabled;
    }

    public String getScenarioKey() { return scenarioKey; }
    public Boolean getEnabled() { return enabled; }
}
