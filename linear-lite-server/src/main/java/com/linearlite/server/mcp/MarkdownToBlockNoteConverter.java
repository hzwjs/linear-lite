package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Block;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** MCP 文档输入的唯一转换入口：Markdown 在服务端转换为前端使用的 BlockNote JSON。 */
@Component
public class MarkdownToBlockNoteConverter {

    private final ObjectMapper objectMapper;
    private final Parser parser = Parser.builder().build();

    public MarkdownToBlockNoteConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String convert(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "[]";
        }
        ArrayNode blocks = objectMapper.createArrayNode();
        Node document = parser.parse(markdown);
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            appendBlock(child, blocks);
        }
        return blocks.toString();
    }

    private void appendBlock(Node node, ArrayNode blocks) {
        if (node instanceof Heading heading) {
            blocks.add(block("heading", inlineContent(heading), Map.of("level", heading.getLevel())));
        } else if (node instanceof Paragraph paragraph) {
            blocks.add(block("paragraph", inlineContent(paragraph), Map.of()));
        } else if (node instanceof BulletList list) {
            appendList(list, "bulletListItem", blocks);
        } else if (node instanceof OrderedList list) {
            appendList(list, "numberedListItem", blocks);
        } else if (node instanceof FencedCodeBlock code) {
            blocks.add(codeBlock(code.getLiteral(), code.getInfo()));
        } else if (node instanceof IndentedCodeBlock code) {
            blocks.add(codeBlock(code.getLiteral(), ""));
        } else if (node instanceof BlockQuote quote) {
            for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph paragraph) {
                    blocks.add(block("quote", inlineContent(paragraph), Map.of()));
                } else {
                    appendBlock(child, blocks);
                }
            }
        } else if (node instanceof ThematicBreak) {
            blocks.add(block("paragraph", objectMapper.createArrayNode(), Map.of()));
        }
    }

    private void appendList(Node list, String type, ArrayNode blocks) {
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem listItem)) {
                continue;
            }
            Node first = listItem.getFirstChild();
            if (first instanceof Paragraph paragraph) {
                ObjectNode block = block(type, inlineContent(paragraph), Map.of());
                ArrayNode children = block.putArray("children");
                for (Node nested = first.getNext(); nested != null; nested = nested.getNext()) {
                    appendBlock(nested, children);
                }
                blocks.add(block);
            }
        }
    }

    private ObjectNode block(String type, ArrayNode content, Map<String, Integer> extraProps) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("id", UUID.randomUUID().toString());
        block.put("type", type);
        ObjectNode props = block.putObject("props");
        props.put("backgroundColor", "default");
        props.put("textColor", "default");
        props.put("textAlignment", "left");
        extraProps.forEach(props::put);
        block.set("content", content);
        block.putArray("children");
        return block;
    }

    private ObjectNode codeBlock(String literal, String info) {
        ObjectNode block = block("codeBlock", objectMapper.createArrayNode(), Map.of());
        block.with("props").put("language", info == null ? "" : info.trim());
        ArrayNode content = block.putArray("content");
        content.add(textNode(literal == null ? "" : literal, Map.of()));
        return block;
    }

    private ArrayNode inlineContent(Node node) {
        ArrayNode content = objectMapper.createArrayNode();
        appendInline(node.getFirstChild(), content, Map.of());
        return content;
    }

    private void appendInline(Node node, ArrayNode content, Map<String, Boolean> styles) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Text text) {
                content.add(textNode(text.getLiteral(), styles));
            } else if (current instanceof Code code) {
                content.add(textNode(code.getLiteral(), Map.of("code", true)));
            } else if (current instanceof Emphasis emphasis) {
                appendInline(emphasis.getFirstChild(), content, merge(styles, "italic"));
            } else if (current instanceof StrongEmphasis strong) {
                appendInline(strong.getFirstChild(), content, merge(styles, "bold"));
            } else if (current instanceof Link link) {
                ObjectNode linkNode = objectMapper.createObjectNode();
                linkNode.put("type", "link");
                linkNode.put("href", link.getDestination());
                ArrayNode linkContent = linkNode.putArray("content");
                appendInline(link.getFirstChild(), linkContent, styles);
                content.add(linkNode);
            } else if (current instanceof HardLineBreak) {
                content.add(textNode("\n", styles));
            } else if (current instanceof HtmlInline html) {
                content.add(textNode(html.getLiteral(), styles));
            } else if (current instanceof Image image) {
                appendInline(image.getFirstChild(), content, styles);
            } else if (current.getFirstChild() != null) {
                appendInline(current.getFirstChild(), content, styles);
            }
        }
    }

    private ObjectNode textNode(String value, Map<String, Boolean> styles) {
        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "text");
        text.put("text", value);
        ObjectNode styleNode = text.putObject("styles");
        styles.forEach(styleNode::put);
        return text;
    }

    private Map<String, Boolean> merge(Map<String, Boolean> styles, String key) {
        Map<String, Boolean> merged = new java.util.LinkedHashMap<>(styles);
        merged.put(key, true);
        return merged;
    }
}
