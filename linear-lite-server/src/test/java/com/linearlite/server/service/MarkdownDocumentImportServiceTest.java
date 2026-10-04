package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentAttachmentResponse;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarkdownDocumentImportServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final ProjectDocumentCommandService documents = mock(ProjectDocumentCommandService.class);
    private final ProjectDocumentAttachmentService attachments = mock(ProjectDocumentAttachmentService.class);
    private final MarkdownDocumentImportService imports = new MarkdownDocumentImportService(
            access, new MarkdownToBlockNoteConverter(json), documents, attachments, new DocumentImageProcessor());
    private String savedContent;

    @BeforeEach void resolveFactory() {
        when(documents.createWithContentFactory(anyLong(), any(), anyLong(), any())).thenAnswer(call -> {
            LongFunction<String> factory = call.getArgument(3);
            savedContent = factory.apply(91L);
            return null;
        });
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }
    private byte[] png() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
    private void importText(String name, String content) {
        imports.importFile(3L, null, file(name, content), List.of(), List.of(), 7L);
    }

    @Test void createsEditableFirstVersionWithFileTitleParentAndStructuredMarkdown() throws Exception {
        imports.importFile(3L, 8L, file("方案.MD", "\uFEFF# 标题\n\n正文 **重点**\n\n- 第一项\n\n```java\nint x = 1;\n```\n\n| 名称 | 值 |\n| --- | --- |\n| A | B |"), List.of(), List.of(), 7L);
        var body = ArgumentCaptor.forClass(CreateProjectDocumentRequest.class);
        verify(documents).createWithContentFactory(eq(3L), body.capture(), eq(7L), any());
        assertThat(body.getValue().title()).isEqualTo("方案");
        assertThat(body.getValue().parentDocumentId()).isEqualTo(8L);
        assertThat(body.getValue().contentFormat()).isEqualTo("BLOCKNOTE");
        var blocks = json.readTree(savedContent);
        assertThat(blocks.findValuesAsText("type")).contains("heading", "paragraph", "bulletListItem", "codeBlock", "table");
        assertThat(blocks.get(0).path("content").get(0).path("text").asText()).isEqualTo("标题");
        verify(access).requireMember(3L, 7L);
    }

    @Test void createsRootAndEmptyMarkdownWithoutBlankTitle() {
        importText("C:\\notes\\空白.md", "");
        verify(documents).createWithContentFactory(eq(3L), eq(new CreateProjectDocumentRequest(null, "空白", null, null, null, "BLOCKNOTE")), eq(7L), any());
        assertThat(savedContent).isEqualTo("[]");
    }

    @Test void rejectsNonMarkdownAndInvalidFileTitlesBeforeCreatingDocument() {
        clearInvocations(documents);
        for (String name : new String[]{"guide.txt", ".md", "  .md", "x".repeat(257) + ".md"})
            assertThatIllegalArgumentException().isThrownBy(() -> importText(name, "hello"));
        verifyNoInteractions(documents);
    }

    @Test void rejectsOversizeFileBeforeReadingAndCreatingDocument() {
        var file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("large.md");
        when(file.getSize()).thenReturn(5L * 1024 * 1024 + 1);
        assertThatIllegalArgumentException().isThrownBy(() -> imports.importFile(3L, null, file, List.of(), List.of(), 7L)).withMessageContaining("5 MiB");
        verifyNoInteractions(documents);
    }

    @Test void rejectsInvalidUtf8AndBinaryContentWithoutSavingCorruptedText() {
        var invalid = new MockMultipartFile("file", "bad.md", "text/markdown", new byte[]{(byte) 0xc3, 0x28});
        assertThatIllegalArgumentException().isThrownBy(() -> imports.importFile(3L, null, invalid, List.of(), List.of(), 7L)).withMessageContaining("UTF-8");
        assertThatIllegalArgumentException().isThrownBy(() -> importText("binary.md", "hello\0world")).withMessageContaining("二进制");
        verifyNoInteractions(documents);
    }

    @Test void inspectsCanonicalPathsAndIgnoresImageSyntaxInsideCode() {
        var preview = imports.inspect(3L, file("images.md", "![图](./图.png)\n\n![重复](图.png)\n\n![嵌套](img/a%20b.png)\n\n![空格](<img/a b.png>)\n\n```md\n![源码](unused.png)\n```"), 7L);
        assertThat(preview.imagePaths()).containsExactly("图.png", "img/a b.png");
        verifyNoInteractions(documents, attachments);
    }

    @Test void requiresMatchingResourcesBeforeCreatingDocument() {
        assertThatIllegalArgumentException().isThrownBy(() -> importText("images.md", "![图](image.png)"))
                .withMessageContaining("补齐");
        var image = new MockMultipartFile("images", "image.png", "image/png", new byte[]{1});
        assertThatIllegalArgumentException().isThrownBy(() -> imports.importFile(3L, null, file("images.md", "![图](image.png)"), List.of("wrong.png"), List.of(image), 7L));
        assertThatIllegalArgumentException().isThrownBy(() -> imports.importFile(3L, null, file("images.md", "![图](image.png)"), List.of("image.png", "image.png"), List.of(image, image), 7L)).withMessageContaining("重复");
        verifyNoInteractions(documents, attachments);
    }

    @Test void uploadsImagesAndPersistsOnlyAssetIdentityInFirstBody() throws Exception {
        var image = new MockMultipartFile("images", "image.png", "application/octet-stream", png());
        when(attachments.uploadImage(eq(91L), eq("image.png"), eq("image/png"), any(), startsWith("markdown-image:"), eq(7L)))
                .thenReturn(new ProjectDocumentAttachmentResponse(51L, 3L, 91L, null, "image.png", 3L, "image/png", null, 10, 10, null, null, null));
        imports.importFile(3L, null, file("images.md", "前文\n\n![图](./image.png)\n\n![重复](image.png)"), List.of("image.png"), List.of(image), 7L);
        var blocks = json.readTree(savedContent);
        assertThat(blocks.findValuesAsText("type")).contains("documentImage");
        assertThat(blocks.findValues("imageAssetId")).hasSize(2);
        assertThat(savedContent).doesNotContain("![", "image.png", "mcp-image:");
        assertThat(blocks.get(1).path("props").path("imageAssetId").asLong()).isEqualTo(51L);
        verify(attachments, times(1)).uploadImage(anyLong(), anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test void rejectsUndecodableImagesBeforeCreatingDocumentOrUploadingResources() throws Exception {
        var good = new MockMultipartFile("images", "good.png", "image/png", png());
        var bad = new MockMultipartFile("images", "bad.png", "image/png", "not-png".getBytes());
        assertThatIllegalArgumentException().isThrownBy(() -> imports.importFile(3L, null,
                file("broken.md", "![好](good.png)\n\n![坏](bad.png)"), List.of("good.png", "bad.png"), List.of(good, bad), 7L))
                .withMessageContaining("可解码").withMessageContaining("bad.png");
        verifyNoInteractions(documents, attachments);
    }

    @Test void rejectsImageLimitsBeforeStartingFactory() {
        MultipartFile large = mock(MultipartFile.class);
        when(large.getSize()).thenReturn(6L * 1024 * 1024 + 1);
        assertThatIllegalArgumentException().isThrownBy(() -> imports.importFile(3L, null, file("images.md", "![图](a.png)"), List.of("a.png"), List.of(large), 7L)).withMessageContaining("6 MiB");
        verifyNoInteractions(documents, attachments);
    }

    @Test void checksMembershipBeforeReadingFile() {
        doThrow(new ForbiddenOperationException("无权限")).when(access).requireMember(3L, 9L);
        var file = mock(MultipartFile.class);
        assertThatThrownBy(() -> imports.inspect(3L, file, 9L)).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(file, documents);
    }

    @Test void convertsDetailsToNativeToggleWithSourceChildren() throws Exception {
        importText("html.md", "# Title\n\n<details>\n<summary>查看源码</summary>\n\n```mermaid\ngraph TD; A-->B\n```\n\n</details>\n\n<section>保留的正文</section>");
        var blocks = json.readTree(savedContent);
        var toggle = blocks.get(1);
        assertThat(toggle.path("type").asText()).isEqualTo("toggleListItem");
        assertThat(toggle.path("content").get(0).path("text").asText()).isEqualTo("查看源码");
        assertThat(toggle.path("children").get(0).path("props").path("language").asText()).isEqualTo("mermaid");
        assertThat(savedContent).contains("graph TD; A-->B", "<section>保留的正文</section>").doesNotContain("<details>", "<summary>", "</details>");
    }

    @Test void handlesNestedAndSingleBlockDetailsAndRejectsUnclosedTags() throws Exception {
        importText("nested.md", "<details><summary>外层</summary>\n\n<DETAILS><SUMMARY>内层</SUMMARY>正文</DETAILS>\n\n</details>");
        var root = json.readTree(savedContent).get(0);
        assertThat(root.path("children").get(0).path("type").asText()).isEqualTo("toggleListItem");
        assertThat(savedContent).contains("外层", "内层", "正文");
        assertThatIllegalArgumentException().isThrownBy(() -> imports.inspect(3L, file("bad.md", "<details><summary>标题</summary>\n\n正文"), 7L)).withMessageContaining("缺少结束");
    }

    @Test void rejectsRemoteImagesInsteadOfRetainingUrlOrFetchingThem() {
        assertThatIllegalArgumentException().isThrownBy(() -> imports.inspect(3L, file("remote.md", "![远程](https://example.com/image.png)"), 7L)).withMessageContaining("相对路径");
        verifyNoInteractions(attachments, documents);
    }
}
