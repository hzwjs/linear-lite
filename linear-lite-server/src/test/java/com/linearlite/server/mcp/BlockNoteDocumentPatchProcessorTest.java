package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockNoteDocumentPatchProcessorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockNoteDocumentPatchProcessor processor = new BlockNoteDocumentPatchProcessor(mapper);

    @Test
    void changesHeadingAndTableCellWithoutRebuildingUnrelatedBlocksOrWidths() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [
                  {"id":"h1","type":"heading","props":{"level":1,"backgroundColor":"default"},"content":[{"type":"text","text":"API"}],"children":[]},
                  {"id":"t1","type":"table","content":{"type":"tableContent","columnWidths":[240,720],"rows":[{"cells":[{"type":"tableCell","props":{"rowspan":1,"colspan":1},"content":[{"type":"text","text":"old"}]},{"type":"tableCell","props":{"rowspan":1,"colspan":1},"content":[{"type":"text","text":"keep"}]}]}]},"children":[]},
                  {"id":"p1","type":"paragraph","props":{"textColor":"default"},"content":[{"type":"text","text":"untouched"}],"children":[]}
                ]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("""
                [
                  {"op":"update_block_props","blockId":"h1","props":{"level":2}},
                  {"op":"update_table_cell","tableBlockId":"t1","rowIndex":0,"columnIndex":0,"content":[{"type":"text","text":"new"}]}
                ]
                """);

        var result = processor.apply(original, ops, Map.of());

        assertEquals(2, result.blocks().get(0).path("props").path("level").asInt());
        assertEquals("default", result.blocks().get(0).path("props").path("backgroundColor").asText());
        assertEquals("new", result.blocks().get(1).path("content").path("rows").get(0)
                .path("cells").get(0).path("content").get(0).path("text").asText());
        assertEquals("keep", result.blocks().get(1).path("content").path("rows").get(0)
                .path("cells").get(1).path("content").get(0).path("text").asText());
        assertEquals(240, result.blocks().get(1).path("content").path("columnWidths").get(0).asInt());
        assertEquals("untouched", result.blocks().get(2).path("content").get(0).path("text").asText());
        assertEquals(original.get(0).path("props").path("level").asInt(), 1);
    }

    @Test
    void insertsTableRowIntoExistingTableAndPreservesItsBlockAndColumnWidths() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [
                  {"id":"heading","type":"heading","props":{"level":1},"content":[{"type":"text","text":"会员服务"}],"children":[]},
                  {"id":"members","type":"table","props":{"textColor":"default"},"content":{"type":"tableContent","columnWidths":[137,137],"rows":[
                    {"cells":[{"type":"tableCell","props":{"colspan":1,"rowspan":1},"content":[{"type":"text","text":"编号"}]},{"type":"tableCell","props":{"colspan":1,"rowspan":1},"content":[{"type":"text","text":"接口名称"}]}]},
                    {"cells":[{"type":"tableCell","props":{"colspan":1,"rowspan":1},"content":[{"type":"text","text":"M-029"}]},{"type":"tableCell","props":{"colspan":1,"rowspan":1},"content":[{"type":"text","text":"原入金专用手机号查询"}]}]}
                  ]},"children":[]},
                  {"id":"next-heading","type":"heading","props":{"level":1},"content":[{"type":"text","text":"权益服务"}],"children":[]}
                ]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("""
                [{"op":"insert_table_row","tableBlockId":"members","rowIndex":2,"row":{"cells":[
                  {"type":"tableCell","props":{"colspan":1,"rowspan":1},"content":[{"type":"text","text":"M-030"}]},
                  {"type":"tableCell","props":{"colspan":1,"rowspan":1},"content":[{"type":"text","text":"获取邀请二维码"}]}
                ]}}]
                """);

        var result = processor.apply(original, ops, Map.of());

        assertEquals(3, result.blocks().size());
        assertEquals("members", result.blocks().get(1).path("id").asText());
        assertEquals(3, result.blocks().get(1).path("content").path("rows").size());
        assertEquals("M-029", result.blocks().get(1).path("content").path("rows").get(1)
                .path("cells").get(0).path("content").get(0).path("text").asText());
        assertEquals("M-030", result.blocks().get(1).path("content").path("rows").get(2)
                .path("cells").get(0).path("content").get(0).path("text").asText());
        assertEquals(137, result.blocks().get(1).path("content").path("columnWidths").get(0).asInt());
        assertEquals("权益服务", result.blocks().get(2).path("content").get(0).path("text").asText());
    }

    @Test
    void replacesTextRangeAndKeepsStylesOutsideRange() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [{"id":"p1","type":"paragraph","content":[
                  {"type":"text","text":"keep ","styles":{"bold":true}},
                  {"type":"text","text":"old"},
                  {"type":"link","href":"https://example.test","content":[{"type":"text","text":" link"}]}
                ],"children":[]}]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("""
                [{"op":"replace_inline_range","blockId":"p1","start":5,"end":8,"expectedText":"old",
                  "content":[{"type":"text","text":"new","styles":{"italic":true}}]}]
                """);

        var result = processor.apply(original, ops, Map.of());

        var content = result.blocks().get(0).path("content");
        assertEquals("keep ", content.get(0).path("text").asText());
        assertEquals(true, content.get(0).path("styles").path("bold").asBoolean());
        assertEquals("new", content.get(1).path("text").asText());
        assertEquals(true, content.get(1).path("styles").path("italic").asBoolean());
        assertEquals("https://example.test", content.get(2).path("href").asText());
    }

    @Test
    void insertsAndMovesBlocksByParentAndAnchorThenDeletesSubtree() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [{"id":"list","type":"bulletListItem","content":[],"children":[
                  {"id":"a","type":"paragraph","content":[{"type":"text","text":"A"}],"children":[]},
                  {"id":"b","type":"paragraph","content":[{"type":"text","text":"B"}],"children":[]}
                ]},{"id":"root","type":"paragraph","content":[],"children":[]}]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("""
                [
                  {"op":"insert_block","parentId":"list","afterBlockId":"a","block":{"type":"paragraph","content":[{"type":"text","text":"C"}],"children":[]}},
                  {"op":"move_block","blockId":"b","parentId":null,"beforeBlockId":"root"},
                  {"op":"delete_block","blockId":"list"}
                ]
                """);

        var result = processor.apply(original, ops, Map.of());

        assertEquals(2, result.blocks().size());
        assertEquals("b", result.blocks().get(0).path("id").asText());
        assertEquals("root", result.blocks().get(1).path("id").asText());
        assertEquals(1, result.createdBlockIds().size());
    }

    @Test
    void rejectsStaleExpectedTextAndDoesNotMutateInput() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("[{\"id\":\"p\",\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"children\":[]}]");
        ArrayNode ops = (ArrayNode) mapper.readTree("[{\"op\":\"replace_inline_range\",\"blockId\":\"p\",\"start\":0,\"end\":5,\"expectedText\":\"stale\",\"content\":[]}]");

        McpDocumentPatchOperationException error = assertThrows(
                McpDocumentPatchOperationException.class, () -> processor.apply(original, ops, Map.of()));
        assertEquals(0, error.getOperationIndex());
        assertEquals("hello", original.get(0).path("content").get(0).path("text").asText());
    }

    @Test
    void rejectsTableCellEditsWhenCellsAreMerged() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [{"id":"t","type":"table","content":{"type":"tableContent","columnWidths":[500],"rows":[{"cells":[{"type":"tableCell","props":{"rowspan":2,"colspan":1},"content":[]}]}]},"children":[]}]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("[{\"op\":\"update_table_cell\",\"tableBlockId\":\"t\",\"rowIndex\":0,\"columnIndex\":0,\"content\":[]}]");

        assertThrows(McpDocumentPatchOperationException.class, () -> processor.apply(original, ops, Map.of()));
    }

    @Test
    void rejectsTableRowInsertionWhenColumnCountDoesNotMatch() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [{"id":"t","type":"table","content":{"type":"tableContent","columnWidths":[240,720],"rows":[{"cells":[
                  {"type":"tableCell","props":{"rowspan":1,"colspan":1},"content":[]},
                  {"type":"tableCell","props":{"rowspan":1,"colspan":1},"content":[]}
                ]}]},"children":[]}]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("""
                [{"op":"insert_table_row","tableBlockId":"t","rowIndex":1,"row":{"cells":[
                  {"type":"tableCell","props":{"rowspan":1,"colspan":1},"content":[]}
                ]}}]
                """);

        assertThrows(McpDocumentPatchOperationException.class, () -> processor.apply(original, ops, Map.of()));
        assertEquals(1, original.get(0).path("content").path("rows").size());
    }

    @Test
    void updatesChecklistAndResolvesInsertedImageReferenceWithoutTouchingExistingImage() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [
                  {"id":"check","type":"checkListItem","props":{"checked":false},"content":[{"type":"text","text":"task"}],"children":[]},
                  {"id":"old-image","type":"documentImage","props":{"imageAssetId":21,"caption":"existing"},"content":[],"children":[]}
                ]
                """);
        ArrayNode ops = (ArrayNode) mapper.readTree("""
                [
                  {"op":"update_block_props","blockId":"check","props":{"checked":true}},
                  {"op":"insert_block","block":{"type":"documentImage","props":{"imageRef":"new-image"},"content":[],"children":[]}}
                ]
                """);

        var result = processor.apply(original, ops, Map.of("new-image", 44L));

        assertEquals(true, result.blocks().get(0).path("props").path("checked").asBoolean());
        assertEquals(21L, result.blocks().get(1).path("props").path("imageAssetId").asLong());
        assertEquals("existing", result.blocks().get(1).path("props").path("caption").asText());
        assertEquals(44L, result.blocks().get(2).path("props").path("imageAssetId").asLong());
        assertEquals(false, result.blocks().get(2).path("props").has("imageRef"));
    }

    @Test
    void rejectsSplitSurrogatePairAndInvalidExistingImageUrl() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("""
                [{"id":"p","type":"paragraph","content":[{"type":"text","text":"A😀B"}],"children":[]}]
                """);
        ArrayNode split = (ArrayNode) mapper.readTree("""
                [{"op":"replace_inline_range","blockId":"p","start":2,"end":3,"expectedText":"","content":[]}]
                """);
        assertThrows(McpDocumentPatchOperationException.class, () -> processor.apply(original, split, Map.of()));

        ArrayNode legacyImage = (ArrayNode) mapper.readTree("""
                [{"id":"i","type":"documentImage","props":{"imageAssetId":21,"url":"https://example.test/a.png"},"content":[],"children":[]}]
                """);
        assertThrows(McpInvalidParamsException.class,
                () -> processor.apply(legacyImage, mapper.createArrayNode(), Map.of()));
    }

    @Test
    void insertedImageMustResolveThroughTargetDocumentAttachmentReference() throws Exception {
        ArrayNode original = (ArrayNode) mapper.readTree("[]");
        ArrayNode operations = (ArrayNode) mapper.readTree("""
                [{"op":"insert_block","block":{"type":"documentImage","props":{"imageAssetId":99},"content":[],"children":[]}}]
                """);

        assertThrows(McpDocumentPatchOperationException.class,
                () -> processor.apply(original, operations, Map.of()));
    }
}
