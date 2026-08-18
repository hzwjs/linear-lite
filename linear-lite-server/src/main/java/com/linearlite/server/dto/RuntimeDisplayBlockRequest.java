package com.linearlite.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Bridge 上报的当前轮临时显示块，不得进入数据库或 session 历史。 */
public class RuntimeDisplayBlockRequest {
    private String blockId;
    private Long revision;
    private String kind;
    private String phase;
    private JsonNode content;
    private JsonNode tool;

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public JsonNode getContent() { return content; }
    public void setContent(JsonNode content) { this.content = content; }
    public JsonNode getTool() { return tool; }
    public void setTool(JsonNode tool) { this.tool = tool; }
}
