package com.linearlite.server.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record SessionDisplayBlock(
        String blockId,
        String entryId,
        String runtimeBlockId,
        Long order,
        String kind,
        String phase,
        JsonNode content,
        JsonNode tool,
        String createdAt) {
}
