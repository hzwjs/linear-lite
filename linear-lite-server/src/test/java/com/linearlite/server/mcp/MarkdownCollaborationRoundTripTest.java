package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownCollaborationRoundTripTest {
    @TempDir Path temporaryDirectory;

    @Test
    void markdownCreationMatchesTheRealCollaborationCodecWithoutLosingLineBreaks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MarkdownToBlockNoteConverter converter = new MarkdownToBlockNoteConverter(mapper);
        String[] markdownCases = {
                "第一行\n第二行\n第三行",
                "第一行  \n第二行\\\n第三行",
                "| 字段 | 说明 |\n| code | 编码 |\n| title | 标题 |",
                "普通\\*文字 <span>内容</span> **加粗** **相邻加粗**",
                "[链接\\*文字](https://example.test) [同址](https://example.test)",
                "[相邻](https://example.test)[链接](https://example.test)",
                "[**加粗**](https://example.test)[斜体 *链接*](https://example.test)",
                "[一](https://one.example.test)[二](https://two.example.test)",
                "[](https://example.test)正文",
                "**加粗 `代码`**，*斜体 `代码`*，**加粗**\\*普通",
                "| 名称 | 值 |\n| :--- | ---: |\n| **加粗** | |\n| A | `code` |",
                "> 引用第一行\n> 第二行\n\n- 父项\n  续行\n  - 子项",
                "<details><summary>说明</summary>\n\n第一行\n第二行\n\n</details>",
                "<details><summary></summary>\n\n正文\n\n</details>",
                "```java\nclass Demo {}\n```\n\n![图](diagram.png)",
                "```\n```\n\n---",
        };
        ArrayNode inputs = mapper.createArrayNode();
        for (String markdown : markdownCases) {
            inputs.add(mapper.readTree(converter.convertFile(markdown,
                    markdown.contains("diagram.png") ? Map.of("diagram.png", 31L) : Map.of())));
        }
        inputs.add(mapper.readTree(converter.convert("MCP 第一行\n第二行")));
        inputs.add(mapper.readTree(converter.convertTaskDescription("任务第一行\n第二行")));

        // 覆盖 Java 转换器 -> 产品 schema -> Yjs -> BlockNote 的真实边界，不能用编码器 mock。
        String script = """
                import { encodeDocument, materializeDocument } from './collaboration-server/src/document-codec.ts';
                let input = '';
                for await (const chunk of process.stdin) input += chunk;
                const output = JSON.parse(input).map(blocks =>
                  JSON.parse(materializeDocument('BLOCKNOTE', encodeDocument('BLOCKNOTE', JSON.stringify(blocks)))));
                process.stdout.write(JSON.stringify(output));
                """;
        Path output = temporaryDirectory.resolve("round-trip.json");
        Path errors = temporaryDirectory.resolve("codec-errors.txt");
        Process process = new ProcessBuilder("node", "--import", "tsx", "--input-type=module", "-e", script)
                .directory(Path.of(System.getProperty("basedir"), "..").toAbsolutePath().normalize().toFile())
                .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(mapper.writeValueAsBytes(inputs));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "协作编码器运行超时");
            assertEquals(0, process.exitValue(), Files.readString(errors));
            JsonNode actual = mapper.readTree(Files.readString(output, StandardCharsets.UTF_8));
            assertAll(IntStream.range(0, inputs.size()).mapToObj(index -> () ->
                    assertEquals(inputs.get(index), actual.get(index), "协作服务编码回读与初始正文不一致，样例 " + index)));
            assertEquals("第一行\n第二行\n第三行", actual.get(0).get(0).path("content").get(0).path("text").asText());
            assertEquals("第一行\n第二行\n第三行", actual.get(1).get(0).path("content").get(0).path("text").asText());
        } finally {
            process.destroyForcibly();
        }
    }
}
