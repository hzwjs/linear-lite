package com.linearlite.server.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollaborationStateMigrationToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertsOnlySameDocumentAttachmentImagesAndPreservesBlockIdentityAndChildren() throws Exception {
        String source = """
                [{"id":"before","type":"paragraph","props":{},"content":"前","children":[]},
                 {"id":"legacy","type":"image","props":{"url":"/api/project-documents/42/attachments/731/download","name":"拓扑","caption":""},"children":[{"id":"child","type":"paragraph","props":{},"content":"子项","children":[]}]},
                 {"id":"after","type":"paragraph","props":{},"content":"后","children":[]}]""";

        CollaborationStateMigrationTool.PreparedContent prepared =
                CollaborationStateMigrationTool.prepareContent(42, "BLOCKNOTE", source);
        JsonNode blocks = mapper.readTree(prepared.content());

        assertThat(prepared.imageConversionCount()).isEqualTo(1);
        assertThat(prepared.imagePlaceholderCount()).isZero();
        assertThat(prepared.defaultAltTextCount()).isZero();
        assertThat(prepared.documentImageDefaultRemovalCount()).isZero();
        assertThat(blocks.get(1).path("id").asText()).isEqualTo("legacy");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("documentImage");
        assertThat(blocks.get(1).path("props").path("imageAssetId").asLong()).isEqualTo(731);
        assertThat(blocks.get(1).path("props").path("altText").asText()).isEqualTo("拓扑");
        assertThat(blocks.get(1).path("props").path("caption").asText()).isEmpty();
        assertThat(blocks.get(1).path("children").get(0).path("id").asText()).isEqualTo("child");
        assertThat(blocks.get(0).path("id").asText()).isEqualTo("before");
        assertThat(blocks.get(2).path("id").asText()).isEqualTo("after");
    }

    @Test
    void rejectsUnapprovedZeroByteDataImageWithDocumentId() {
        String source = "[{\"id\":\"bad-image\",\"type\":\"image\",\"props\":{\"url\":\"data:image/png;base64,\"},\"children\":[]}]";

        assertThatThrownBy(() -> CollaborationStateMigrationTool.prepareContent(561, "BLOCKNOTE", source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId=561")
                .hasMessageContaining("blockId=bad-image");
    }

    @Test
    void replacesTruncatedZeroByteDataImageWithUnavailableText() throws Exception {
        String source = "[{\"id\":\"56aba7dc-3be2-470e-a6e9-c710034296be\",\"type\":\"image\",\"props\":{\"url\":\"data:image/png;base64...\"},\"children\":[]},{\"id\":\"f48b3ec2-5f6e-4e77-8ac3-e95d7c2ef6ef\",\"type\":\"image\",\"props\":{\"url\":\"data:image/png;base64...\"},\"children\":[]}]";

        CollaborationStateMigrationTool.PreparedContent prepared =
                CollaborationStateMigrationTool.prepareContent(561, "BLOCKNOTE", source);
        JsonNode blocks = mapper.readTree(prepared.content());

        assertThat(prepared.imagePlaceholderCount()).isEqualTo(2);
        assertThat(blocks.get(0).path("id").asText()).isEqualTo("56aba7dc-3be2-470e-a6e9-c710034296be");
        assertThat(blocks.get(0).path("content").get(0).path("text").asText()).isEqualTo("图片不可用");
        assertThat(blocks.get(1).path("id").asText()).isEqualTo("f48b3ec2-5f6e-4e77-8ac3-e95d7c2ef6ef");
        assertThat(blocks.get(1).path("content").get(0).path("text").asText()).isEqualTo("图片不可用");
    }

    @Test
    void rejectsCrossDocumentOrMalformedImageUrlsWithDocumentId() {
        String source = "[{\"id\":\"foreign\",\"type\":\"image\",\"props\":{\"url\":\"/api/project-documents/43/attachments/731/download\"},\"children\":[]}]";

        assertThatThrownBy(() -> CollaborationStateMigrationTool.prepareContent(42, "BLOCKNOTE", source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId=42")
                .hasMessageContaining("blockId=foreign");
    }

    @Test
    void acceptsOnlyObservedBlockNoteSchemaCanonicalization() throws Exception {
        String expected = """
                [{"id":"h","type":"heading","props":{"level":2},"content":[],"children":[]},
                 {"id":"c","type":"codeBlock","props":{"language":"text","backgroundColor":"default","textColor":"default","textAlignment":"left"},"content":[{"type":"text","text":"ab","styles":{}}],"children":[]},
                 {"id":"p","type":"paragraph","props":{},"content":[{"type":"text","text":"甲","styles":{}},{"type":"text","text":"乙","styles":{}}],"children":[]}]""";
        String decoded = """
                [{"id":"h","type":"heading","props":{"level":2,"isToggleable":false},"content":[],"children":[]},
                 {"id":"c","type":"codeBlock","props":{"language":"text"},"content":[{"type":"text","text":"ab","styles":{}}],"children":[]},
                 {"id":"p","type":"paragraph","props":{},"content":[{"type":"text","text":"甲乙","styles":{}}],"children":[]}]""";

        assertThat(CollaborationStateMigrationTool.sameContent("BLOCKNOTE", expected, decoded)).isTrue();
    }

    @Test
    void addsEmptyAltTextOnlyWhenLegacyDocumentImageOmitsIt() throws Exception {
        String source = "[{\"id\":\"image\",\"type\":\"documentImage\",\"props\":{\"imageAssetId\":17,\"caption\":\"\"},\"children\":[]}]";

        CollaborationStateMigrationTool.PreparedContent prepared =
                CollaborationStateMigrationTool.prepareContent(42, "BLOCKNOTE", source);
        JsonNode block = mapper.readTree(prepared.content()).get(0);

        assertThat(prepared.imageConversionCount()).isZero();
        assertThat(prepared.defaultAltTextCount()).isEqualTo(1);
        assertThat(prepared.documentImageDefaultRemovalCount()).isZero();
        assertThat(block.path("props").path("imageAssetId").asLong()).isEqualTo(17);
        assertThat(block.path("props").path("altText").asText()).isEmpty();
        assertThat(CollaborationStateMigrationTool.sameContent("BLOCKNOTE", prepared.content(),
                "[{\"id\":\"image\",\"type\":\"documentImage\",\"props\":{\"imageAssetId\":17,\"caption\":\"\"},\"children\":[]}]"))
                .isFalse();
    }

    @Test
    void removesEmptyTextRunsWithoutContentFromInlineContentArrays() throws Exception {
        String source = "[{\"id\":\"table\",\"type\":\"table\",\"props\":{},\"content\":{\"type\":\"tableContent\",\"rows\":[{\"cells\":[{\"content\":[{\"type\":\"text\",\"text\":\"\",\"styles\":{}},{\"type\":\"text\",\"text\":\"x\",\"styles\":{}}]}]}]},\"children\":[]},{\"id\":\"paragraph\",\"type\":\"paragraph\",\"props\":{},\"content\":[{\"type\":\"text\",\"text\":\"\",\"styles\":{\"bold\":true}},{\"type\":\"text\",\"text\":\"\",\"styles\":{},\"custom\":1}],\"children\":[]}]";

        CollaborationStateMigrationTool.PreparedContent prepared =
                CollaborationStateMigrationTool.prepareContent(42, "BLOCKNOTE", source);
        JsonNode blocks = mapper.readTree(prepared.content());

        assertThat(prepared.emptyInlineTextRemovalCount()).isEqualTo(2);
        assertThat(blocks.get(0).path("content").path("rows").get(0).path("cells").get(0)
                .path("content")).hasSize(1);
        assertThat(blocks.get(1).path("content")).hasSize(1);
    }

    @Test
    void rejectsNonDefaultCodeBlockPropsAndLostText() throws Exception {
        String expected = "[{\"id\":\"c\",\"type\":\"codeBlock\",\"props\":{\"language\":\"text\",\"textColor\":\"red\"},\"content\":\"abc\",\"children\":[]}]";
        String noColor = "[{\"id\":\"c\",\"type\":\"codeBlock\",\"props\":{\"language\":\"text\"},\"content\":\"abc\",\"children\":[]}]";
        String lostText = "[{\"id\":\"c\",\"type\":\"codeBlock\",\"props\":{\"language\":\"text\"},\"content\":\"ab\",\"children\":[]}]";

        assertThat(CollaborationStateMigrationTool.sameContent("BLOCKNOTE", expected, noColor)).isFalse();
        assertThat(CollaborationStateMigrationTool.sameContent("BLOCKNOTE", expected, lostText)).isFalse();
        assertThat(CollaborationStateMigrationTool.sameContent("BLOCKNOTE",
                "[{\"id\":\"i\",\"type\":\"documentImage\",\"props\":{\"imageAssetId\":3,\"caption\":\"\",\"altText\":\"示意\"},\"children\":[]}]",
                "[{\"id\":\"i\",\"type\":\"documentImage\",\"props\":{\"imageAssetId\":3,\"caption\":\"\",\"altText\":\"\"},\"children\":[]}]"))
                .isFalse();
        assertThat(CollaborationStateMigrationTool.sameContent("BLOCKNOTE",
                "[{\"id\":\"p\",\"type\":\"paragraph\",\"props\":{},\"content\":[{\"type\":\"text\",\"text\":\"\",\"styles\":{},\"custom\":1}],\"children\":[]}]",
                "[{\"id\":\"p\",\"type\":\"paragraph\",\"props\":{},\"content\":[],\"children\":[]}]"))
                .isFalse();
    }
}
