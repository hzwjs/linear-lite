package com.linearlite.server.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;

/** HTML 源码是正文的唯一事实；解析产物只用于预览和搜索，不回写源码。 */
public final class HtmlDocumentContent {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final String PREVIEW_CSP = "default-src 'none'; script-src 'unsafe-inline'; "
            + "style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; "
            + "connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";

    private HtmlDocumentContent() { }

    public static String requireSource(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("HTML 正文不能为空");
        if (source.indexOf('\0') >= 0) throw new IllegalArgumentException("HTML 正文包含无效字符");
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("HTML 正文不能超过 5 MiB");
        }
        return source;
    }

    public static String preview(String source) {
        Document html = Jsoup.parse(requireSource(source));
        html.outputSettings().prettyPrint(false);
        // 去掉文档提供的导航指令；应用策略必须位于所有资源与脚本之前。
        html.select("base, meta[http-equiv]").remove();
        html.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", PREVIEW_CSP);
        return html.outerHtml();
    }

    public static String searchText(String source) {
        Document html = Jsoup.parse(source);
        html.select("script, style, template, noscript, [hidden], [aria-hidden=true]").remove();
        return html.body().text();
    }
}
