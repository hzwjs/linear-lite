package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownToBlockNoteConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MarkdownToBlockNoteConverter converter = new MarkdownToBlockNoteConverter(objectMapper);

    @Test
    void convertsMarkdownBlocksToRenderableBlockNoteJson() throws Exception {
        JsonNode blocks = objectMapper.readTree(converter.convert(
                "# Title\n\nBody with **bold**.\n\n- one\n- two\n\n```java\nclass Demo {}\n```"));

        assertEquals(5, blocks.size());
        assertEquals("heading", blocks.get(0).path("type").asText());
        assertTrue(blocks.get(0).path("id").isTextual());
        assertEquals("paragraph", blocks.get(1).path("type").asText());
        assertEquals("bulletListItem", blocks.get(2).path("type").asText());
        assertEquals("codeBlock", blocks.get(4).path("type").asText());
        assertEquals("java", blocks.get(4).path("props").path("language").asText());
    }

    @Test
    void convertsEmptyMarkdownToEmptyDocument() {
        assertEquals("[]", converter.convert("  \n"));
    }

    @Test
    void convertsGfmTableToNativeBlockNoteTable() throws Exception {
        JsonNode blocks = objectMapper.readTree(converter.convert("""
                | Core 能力 | 职责 |
                |:---|:---:|
                | 值与类型 | 定义输入与输出类型 |
                | 执行器 | 执行不可变计划 |
                """));

        assertEquals(1, blocks.size());
        JsonNode table = blocks.get(0);
        assertEquals("table", table.path("type").asText());
        assertEquals("tableContent", table.path("content").path("type").asText());
        assertEquals(2, table.path("content").path("columnWidths").size());
        JsonNode rows = table.path("content").path("rows");
        assertEquals(3, rows.size());
        assertEquals("Core 能力", rows.get(0).path("cells").get(0).path("content").get(0).path("text").asText());
        assertTrue(rows.get(0).path("cells").get(0).path("content").get(0).path("styles").path("bold").asBoolean());
        assertEquals("center", rows.get(0).path("cells").get(1).path("props").path("textAlignment").asText());
        assertEquals("定义输入与输出类型", rows.get(1).path("cells").get(1).path("content").get(0).path("text").asText());
    }

    @Test
    void convertsMixedTextAndImageIntoAssetIdBlocks() throws Exception {
        JsonNode blocks = objectMapper.readTree(converter.convert(
                "before ![diagram](mcp-image:diagram_1) after", java.util.Map.of("diagram_1", 31L)));

        assertEquals(3, blocks.size());
        assertEquals("paragraph", blocks.get(0).path("type").asText());
        assertEquals("before ", blocks.get(0).path("content").get(0).path("text").asText());
        assertEquals("documentImage", blocks.get(1).path("type").asText());
        assertEquals(31L, blocks.get(1).path("props").path("imageAssetId").asLong());
        assertEquals("diagram", blocks.get(1).path("props").path("caption").asText());
        assertEquals("paragraph", blocks.get(2).path("type").asText());
        assertEquals(" after", blocks.get(2).path("content").get(0).path("text").asText());
    }

    @Test
    void rejectsExternalMarkdownImages() {
        assertThrows(McpInvalidParamsException.class,
                () -> converter.imageReferences("![diagram](https://example.com/diagram.png)"));
    }

    @Test
    void rejectsMissingAndUnusedImageReferences() {
        assertThrows(McpInvalidParamsException.class,
                () -> converter.convert("![diagram](mcp-image:missing)", java.util.Map.of()));
        assertThrows(McpInvalidParamsException.class,
                () -> converter.convert("text", java.util.Map.of("unused", 31L)));
    }
}
