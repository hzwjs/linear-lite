package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
