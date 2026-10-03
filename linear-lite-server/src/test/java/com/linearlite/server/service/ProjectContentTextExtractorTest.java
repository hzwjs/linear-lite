package com.linearlite.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectContentTextExtractorTest {
    @Test
    void extractsVisibleBlockNoteTextWithoutMetadata() {
        String content = """
                [{"id":"secret-id","type":"paragraph","props":{"backgroundColor":"secret-color"},
                  "content":[{"type":"text","text":"可见正文","styles":{}},
                             {"type":"mention","props":{"label":"项目成员"}}],"children":[]}]
                """;

        assertEquals("可见正文 项目成员", ProjectContentTextExtractor.extract(ProjectContentType.DOCUMENT, content, "BLOCKNOTE"));
    }

    @Test
    void malformedDocumentHasNoRawJsonFallback() {
        assertEquals("", ProjectContentTextExtractor.extract(ProjectContentType.DOCUMENT, "not-json", "BLOCKNOTE"));
    }

    @Test
    void taskUsesItsPersistedLegacyHtmlPath() {
        assertEquals("旧任务 正文", ProjectContentTextExtractor.extract(
                ProjectContentType.TASK, "<p>旧任务&nbsp;正文</p>", null));
    }
    @Test
    void htmlSearchIndexesBodyWithoutScriptsStylesOrHiddenMetadata() {
        String html = "<title>metadata</title><style>secret-css</style><body><h1>HTML 标题</h1>"
                + "<p>正文 &amp; 数据</p><script>secret-script</script><span hidden>secret-hidden</span></body>";
        assertEquals("HTML 标题 正文 & 数据", ProjectContentTextExtractor.extract(ProjectContentType.DOCUMENT, html, "HTML"));
    }

}
