package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从任务描述或 BlockNote 文档中提取唯一的用户可见搜索文本。 */
final class ProjectContentTextExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProjectContentTextExtractor() {
    }

    static String extract(ProjectContentType contentType, String sourceContent) {
        if (sourceContent == null || sourceContent.isBlank()) return "";
        if (contentType == ProjectContentType.TASK && !sourceContent.stripLeading().startsWith("[")) {
            return sourceContent.replaceAll("(?s)<[^>]*>", " ")
                    .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        }
        try {
            JsonNode blocks = OBJECT_MAPPER.readTree(sourceContent);
            if (!blocks.isArray()) return "";
            StringBuilder text = new StringBuilder();
            appendBlocks(blocks, text);
            return text.toString().replaceAll("\\s+", " ").trim();
        } catch (Exception malformedBlockNote) {
            // 文档内容没有第二种格式；无法解析时不猜测或回退到原始 JSON。
            return "";
        }
    }

    private static void appendBlocks(JsonNode blocks, StringBuilder text) {
        for (JsonNode block : blocks) {
            appendInlineContent(block.path("content"), text);
            JsonNode children = block.path("children");
            if (children.isArray()) appendBlocks(children, text);
        }
    }

    private static void appendInlineContent(JsonNode content, StringBuilder text) {
        if (!content.isArray()) return;
        for (JsonNode inline : content) {
            String type = inline.path("type").asText("");
            if ("text".equals(type)) append(text, inline.path("text").asText(""));
            if ("mention".equals(type)) append(text, inline.path("props").path("label").asText(""));
            appendInlineContent(inline.path("content"), text);
        }
    }

    private static void append(StringBuilder target, String value) {
        if (value.isBlank()) return;
        if (!target.isEmpty()) target.append(' ');
        target.append(value);
    }
}
