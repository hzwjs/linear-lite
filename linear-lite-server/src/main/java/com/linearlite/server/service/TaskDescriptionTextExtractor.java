package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts only user-visible text from the persisted task description format. */
final class TaskDescriptionTextExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TaskDescriptionTextExtractor() {
    }

    static String extract(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String trimmed = description.trim();
        if (!trimmed.startsWith("[")) {
            return trimmed.replaceAll("(?s)<[^>]*>", " ")
                    .replace("&nbsp;", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        try {
            JsonNode blocks = OBJECT_MAPPER.readTree(trimmed);
            if (!blocks.isArray()) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            appendBlocks(blocks, text);
            return text.toString().replaceAll("\\s+", " ").trim();
        } catch (Exception malformedBlockNote) {
            // A malformed BlockNote document has no reliable visible-text path.
            return "";
        }
    }

    private static void appendBlocks(JsonNode blocks, StringBuilder text) {
        for (JsonNode block : blocks) {
            appendInlineContent(block.path("content"), text);
            JsonNode children = block.path("children");
            if (children.isArray()) {
                appendBlocks(children, text);
            }
        }
    }

    private static void appendInlineContent(JsonNode content, StringBuilder text) {
        if (!content.isArray()) {
            return;
        }
        for (JsonNode inline : content) {
            String type = inline.path("type").asText("");
            if ("text".equals(type)) {
                append(text, inline.path("text").asText(""));
            } else if ("mention".equals(type)) {
                append(text, inline.path("props").path("label").asText(""));
            }
            // Links and other inline containers expose their visible label through nested content.
            appendInlineContent(inline.path("content"), text);
        }
    }

    private static void append(StringBuilder target, String value) {
        if (value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
        }
        target.append(value);
    }
}
