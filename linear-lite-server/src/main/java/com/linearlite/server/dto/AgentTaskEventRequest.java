package com.linearlite.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class AgentTaskEventRequest {
    private Long sequenceNo;
    private String eventType;
    private String summary;
    private JsonNode payload;

    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long sequenceNo) { this.sequenceNo = sequenceNo; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
}
