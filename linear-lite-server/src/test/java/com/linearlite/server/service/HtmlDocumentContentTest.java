package com.linearlite.server.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlDocumentContentTest {
    @Test
    void keepsSourceAndScriptsWhilePreviewPolicyPrecedesDocumentResources() {
        String source = "\n<!doctype html><html lang='zh'><head><meta http-equiv='refresh' content='0;url=https://example.com'>"
                + "<base href='https://example.com'><style>body{color:red}</style></head>"
                + "<body><button onclick='counter++'>计数</button><script>let counter=0;</script></body></html>\n";
        assertEquals(source, HtmlDocumentContent.requireSource(source));
        var preview = Jsoup.parse(HtmlDocumentContent.preview(source));
        assertEquals("zh", preview.selectFirst("html").attr("lang"));
        assertEquals(HtmlDocumentContent.PREVIEW_CSP, preview.head().child(0).attr("content"));
        assertEquals(1, preview.select("meta[http-equiv]").size());
        assertTrue(preview.select("base").isEmpty());
        assertEquals("counter++", preview.selectFirst("button").attr("onclick"));
        assertEquals("let counter=0;", preview.selectFirst("script").data());
        assertTrue(HtmlDocumentContent.PREVIEW_CSP.contains("connect-src 'none'"));
    }

    @Test
    void rejectsBlankNullCharacterAndUtf8ByteOverflow() {
        assertThrows(IllegalArgumentException.class, () -> HtmlDocumentContent.requireSource(" "));
        assertThrows(IllegalArgumentException.class, () -> HtmlDocumentContent.requireSource("<p>\0</p>"));
        assertThrows(IllegalArgumentException.class, () -> HtmlDocumentContent.requireSource("中".repeat(2_000_000)));
    }
}
