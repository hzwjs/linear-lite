package com.linearlite.server.dto;

import java.util.ArrayList;
import java.util.List;

public class AgentTaskEventBatchRequest {
    private String executionId;
    private List<AgentTaskEventRequest> events = new ArrayList<>();

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public List<AgentTaskEventRequest> getEvents() { return events; }
    public void setEvents(List<AgentTaskEventRequest> events) { this.events = events; }
}
