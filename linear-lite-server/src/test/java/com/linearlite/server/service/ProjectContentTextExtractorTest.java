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

        assertEquals("可见正文 项目成员", ProjectContentTextExtractor.extract(ProjectContentType.DOCUMENT, content));
    }

    @Test
    void malformedDocumentHasNoRawJsonFallback() {
        assertEquals("", ProjectContentTextExtractor.extract(ProjectContentType.DOCUMENT, "not-json"));
    }

    @Test
    void taskUsesItsPersistedLegacyHtmlPath() {
        assertEquals("旧任务 正文", ProjectContentTextExtractor.extract(
                ProjectContentType.TASK, "<p>旧任务&nbsp;正文</p>"));
    }
}
