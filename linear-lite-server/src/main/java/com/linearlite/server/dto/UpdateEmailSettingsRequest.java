package com.linearlite.server.dto;

import java.util.List;

public class UpdateEmailSettingsRequest {
    private List<Item> items;

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public static class Item {
        private String scenarioKey;
        private Boolean enabled;

        public String getScenarioKey() { return scenarioKey; }
        public void setScenarioKey(String scenarioKey) { this.scenarioKey = scenarioKey; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
