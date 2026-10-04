package com.linearlite.server.service;

import com.linearlite.server.controller.ProjectDocumentImportController;
import com.linearlite.server.mcp.MarkdownToBlockNoteConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** HTTP/JWT/Markdown 转换/文档事务验收；复用内存 Mapper，不连接数据库。 */
@Configuration
@Import({HtmlDocumentAcceptanceServer.class, ProjectDocumentImportController.class,
        MarkdownDocumentImportService.class, MarkdownToBlockNoteConverter.class, DocumentImageProcessor.class})
public class MarkdownDocumentAcceptanceServer {
    public static void main(String[] args) {
        SpringApplication.run(MarkdownDocumentAcceptanceServer.class,
                "--server.port=19084", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--jwt.secret=markdown-acceptance-fixture-key-not-a-production-secret-2026");
    }
}
