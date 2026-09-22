package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.node.BlockQuote;
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
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** MCP 文档输入的唯一转换入口：Markdown 在服务端转换为前端使用的 BlockNote JSON。 */
@Component
public class MarkdownToBlockNoteConverter {

    private static final String IMAGE_REF_PREFIX = "mcp-image:";

    private final ObjectMapper objectMapper;
    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    public MarkdownToBlockNoteConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String convert(String markdown) {
        return convert(markdown, Map.of());
    }

    public Set<String> imageReferences(String markdown) {
        Set<String> refs = new java.util.LinkedHashSet<>();
        if (markdown == null || markdown.isBlank()) {
            return refs;
        }
        Node document = parser.parse(markdown);
        collectImageReferences(document, refs);
        return Set.copyOf(refs);
    }

    public String convert(String markdown, Map<String, Long> imageAssetIds) {
        if (markdown == null || markdown.isBlank()) {
            if (!imageAssetIds.isEmpty()) {
                throw new McpInvalidParamsException("空正文不能包含未使用的图片资源");
            }
            return "[]";
        }
        ArrayNode blocks = objectMapper.createArrayNode();
        Set<String> usedImageRefs = new HashSet<>();
        Node document = parser.parse(markdown);
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            appendBlock(child, blocks, imageAssetIds, usedImageRefs);
        }
        if (!usedImageRefs.equals(imageAssetIds.keySet())) {
            throw new McpInvalidParamsException("Markdown 图片引用与 images 不匹配");
        }
        return blocks.toString();
    }

    private void collectImageReferences(Node node, Set<String> refs) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Image image) {
                refs.add(imageRef(image));
            }
            if (current.getFirstChild() != null) {
                collectImageReferences(current.getFirstChild(), refs);
            }
        }
    }

    private void appendBlock(Node node, ArrayNode blocks, Map<String, Long> imageAssetIds,
                             Set<String> usedImageRefs) {
        if (node instanceof Heading heading) {
            appendInlineBlocks(heading.getFirstChild(), "heading", Map.of("level", heading.getLevel()),
                    blocks, imageAssetIds, usedImageRefs);
        } else if (node instanceof TableBlock table) {
            appendTable(table, blocks, imageAssetIds, usedImageRefs);
        } else if (node instanceof Paragraph paragraph) {
            appendInlineBlocks(paragraph.getFirstChild(), "paragraph", Map.of(),
                    blocks, imageAssetIds, usedImageRefs);
        } else if (node instanceof BulletList list) {
            appendList(list, "bulletListItem", blocks, imageAssetIds, usedImageRefs);
        } else if (node instanceof OrderedList list) {
            appendList(list, "numberedListItem", blocks, imageAssetIds, usedImageRefs);
        } else if (node instanceof FencedCodeBlock code) {
            blocks.add(codeBlock(code.getLiteral(), code.getInfo()));
        } else if (node instanceof IndentedCodeBlock code) {
            blocks.add(codeBlock(code.getLiteral(), ""));
        } else if (node instanceof BlockQuote quote) {
            for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph paragraph) {
                    appendInlineBlocks(paragraph.getFirstChild(), "quote", Map.of(),
                            blocks, imageAssetIds, usedImageRefs);
                } else {
                    appendBlock(child, blocks, imageAssetIds, usedImageRefs);
                }
            }
        } else if (node instanceof ThematicBreak) {
            blocks.add(block("paragraph", objectMapper.createArrayNode(), Map.of()));
        }
    }

    private void appendTable(TableBlock source, ArrayNode blocks, Map<String, Long> imageAssetIds,
                             Set<String> usedImageRefs) {
        ArrayNode rows = objectMapper.createArrayNode();
        int columnCount = 0;
        for (Node section = source.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow tableRow)) {
                    continue;
                }
                ObjectNode rowNode = objectMapper.createObjectNode();
                ArrayNode cells = rowNode.putArray("cells");
                int rowColumns = 0;
                for (Node node = tableRow.getFirstChild(); node != null; node = node.getNext()) {
                    if (!(node instanceof TableCell cell)) {
                        continue;
                    }
                    if (containsImage(cell)) {
                        throw new McpInvalidParamsException("Markdown 表格单元格不能包含图片");
                    }
                    ArrayNode cellContent = objectMapper.createArrayNode();
                    Map<String, Boolean> styles = cell.isHeader() ? Map.of("bold", true) : Map.of();
                    appendInline(cell.getFirstChild(), cellContent, styles, "paragraph", Map.of(),
                            objectMapper.createArrayNode(), imageAssetIds, usedImageRefs, false);
                    ObjectNode cellNode = objectMapper.createObjectNode();
                    cellNode.put("type", "tableCell");
                    cellNode.set("content", cellContent);
                    ObjectNode props = cellNode.putObject("props");
                    props.put("colspan", 1);
                    props.put("rowspan", 1);
                    props.put("backgroundColor", "default");
                    props.put("textColor", "default");
                    props.put("textAlignment", cellAlignment(cell));
                    cells.add(cellNode);
                    rowColumns++;
                }
                columnCount = Math.max(columnCount, rowColumns);
                rows.add(rowNode);
            }
        }
        if (columnCount == 0 || rows.isEmpty()) {
            return;
        }

        ObjectNode table = block("table", objectMapper.createArrayNode(), Map.of());
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "tableContent");
        ArrayNode columnWidths = content.putArray("columnWidths");
        int width = Math.max(1, 960 / columnCount);
        for (int column = 0; column < columnCount; column++) {
            columnWidths.add(width);
        }
        content.set("rows", rows);
        table.set("content", content);
        blocks.add(table);
    }

    private String cellAlignment(TableCell cell) {
        if (cell.getAlignment() == null) {
            return "left";
        }
        return switch (cell.getAlignment()) {
            case LEFT -> "left";
            case CENTER -> "center";
            case RIGHT -> "right";
        };
    }

    private void appendList(Node list, String type, ArrayNode blocks,
                            Map<String, Long> imageAssetIds, Set<String> usedImageRefs) {
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem listItem)) {
                continue;
            }
            Node first = listItem.getFirstChild();
            if (first instanceof Paragraph paragraph) {
                ArrayNode segments = objectMapper.createArrayNode();
                appendInlineBlocks(paragraph.getFirstChild(), type, Map.of(), segments,
                        imageAssetIds, usedImageRefs);

                ObjectNode listBlock;
                int firstChild = 0;
                if (!segments.isEmpty() && type.equals(segments.get(0).path("type").asText())) {
                    listBlock = (ObjectNode) segments.get(0);
                    firstChild = 1;
                } else {
                    listBlock = block(type, objectMapper.createArrayNode(), Map.of());
                }
                ArrayNode children = listBlock.withArray("children");
                for (int index = firstChild; index < segments.size(); index++) {
                    ObjectNode segment = (ObjectNode) segments.get(index);
                    if (!"documentImage".equals(segment.path("type").asText())) {
                        segment.put("type", "paragraph");
                    }
                    children.add(segment);
                }
                for (Node nested = first.getNext(); nested != null; nested = nested.getNext()) {
                    appendBlock(nested, children, imageAssetIds, usedImageRefs);
                }
                blocks.add(listBlock);
            }
        }
    }

    private void appendInlineBlocks(Node node, String type, Map<String, Integer> extraProps,
                                   ArrayNode blocks, Map<String, Long> imageAssetIds,
                                   Set<String> usedImageRefs) {
        ArrayNode content = objectMapper.createArrayNode();
        appendInline(node, content, Map.of(), type, extraProps, blocks, imageAssetIds, usedImageRefs, false);
        flushInlineBlock(type, extraProps, content, blocks);
    }

    private void appendInline(Node node, ArrayNode content, Map<String, Boolean> styles,
                              String blockType, Map<String, Integer> extraProps, ArrayNode blocks,
                              Map<String, Long> imageAssetIds, Set<String> usedImageRefs,
                              boolean insideLink) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Text text) {
                content.add(textNode(text.getLiteral(), styles));
            } else if (current instanceof Code code) {
                content.add(textNode(code.getLiteral(), Map.of("code", true)));
            } else if (current instanceof Emphasis emphasis) {
                appendInline(emphasis.getFirstChild(), content, merge(styles, "italic"), blockType,
                        extraProps, blocks, imageAssetIds, usedImageRefs, insideLink);
            } else if (current instanceof StrongEmphasis strong) {
                appendInline(strong.getFirstChild(), content, merge(styles, "bold"), blockType,
                        extraProps, blocks, imageAssetIds, usedImageRefs, insideLink);
            } else if (current instanceof Link link) {
                if (containsImage(link)) {
                    throw new McpInvalidParamsException("图片不能嵌入 Markdown 链接");
                }
                ObjectNode linkNode = objectMapper.createObjectNode();
                linkNode.put("type", "link");
                linkNode.put("href", link.getDestination());
                ArrayNode linkContent = linkNode.putArray("content");
                appendInline(link.getFirstChild(), linkContent, styles, blockType,
                        extraProps, blocks, imageAssetIds, usedImageRefs, true);
                content.add(linkNode);
            } else if (current instanceof HardLineBreak) {
                content.add(textNode("\n", styles));
            } else if (current instanceof HtmlInline html) {
                content.add(textNode(html.getLiteral(), styles));
            } else if (current instanceof Image image) {
                if (insideLink) {
                    throw new McpInvalidParamsException("图片不能嵌入 Markdown 链接");
                }
                String ref = imageRef(image);
                Long assetId = imageAssetIds.get(ref);
                if (assetId == null || assetId <= 0) {
                    throw new McpInvalidParamsException("图片资源不存在: " + ref);
                }
                flushInlineBlock(blockType, extraProps, content, blocks);
                blocks.add(documentImage(assetId, imageCaption(image)));
                usedImageRefs.add(ref);
            } else if (current.getFirstChild() != null) {
                appendInline(current.getFirstChild(), content, styles, blockType, extraProps,
                        blocks, imageAssetIds, usedImageRefs, insideLink);
            }
        }
    }

    private boolean containsImage(Node node) {
        for (Node current = node.getFirstChild(); current != null; current = current.getNext()) {
            if (current instanceof Image || containsImage(current)) {
                return true;
            }
        }
        return false;
    }

    private String imageRef(Image image) {
        String destination = image.getDestination();
        if (destination == null || !destination.startsWith(IMAGE_REF_PREFIX)) {
            throw new McpInvalidParamsException(
                    "Markdown 图片必须使用 mcp-image:<ref> 并在 images 提供附件资源");
        }
        String ref = destination.substring(IMAGE_REF_PREFIX.length());
        if (!ref.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new McpInvalidParamsException("Markdown 图片 ref 格式无效");
        }
        return ref;
    }

    private String imageCaption(Image image) {
        StringBuilder caption = new StringBuilder();
        appendPlainText(image.getFirstChild(), caption);
        return caption.toString();
    }

    private void appendPlainText(Node node, StringBuilder text) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Text value) {
                text.append(value.getLiteral());
            } else if (current instanceof Code value) {
                text.append(value.getLiteral());
            } else if (current.getFirstChild() != null) {
                appendPlainText(current.getFirstChild(), text);
            }
        }
    }

    private void flushInlineBlock(String type, Map<String, Integer> extraProps,
                                  ArrayNode content, ArrayNode blocks) {
        if (!content.isEmpty()) {
            blocks.add(block(type, content.deepCopy(), extraProps));
            content.removeAll();
        }
    }

    private ObjectNode documentImage(Long assetId, String caption) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("id", UUID.randomUUID().toString());
        block.put("type", "documentImage");
        ObjectNode props = block.putObject("props");
        props.put("backgroundColor", "default");
        props.put("textColor", "default");
        props.put("textAlignment", "left");
        props.put("imageAssetId", assetId);
        props.put("caption", caption);
        block.putArray("content");
        block.putArray("children");
        return block;
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
