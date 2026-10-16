package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import java.net.URI;
import java.nio.file.Path;
import org.jsoup.Jsoup;

/** MCP 文档与任务描述的 Markdown 转换入口。 */
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

    private static final Pattern DETAILS_HEADER = Pattern.compile(
            "(?is)^\\s*<details(?:\\s[^>]*)?>\\s*<summary>(.*?)</summary>\\s*");
    private static final Pattern DETAILS_TAG = Pattern.compile("(?is)</?details(?:\\s[^>]*)?>");
    private static final Pattern DETAILS_END = Pattern.compile("(?is)</details>\\s*$");

    private static final class DetailsBlock extends CustomBlock {
        private final String title;
        private DetailsBlock(String title) { this.title = title; }
    }

    public List<String> fileImageReferences(String markdown) {
        Node document = parser.parse(markdown);
        prepareDetails(document);
        Set<String> refs = new LinkedHashSet<>();
        collectFileImageReferences(document.getFirstChild(), refs);
        return List.copyOf(refs);
    }

    /** 本地图片转为资源身份，details 转为编辑器原生折叠结构。 */
    public String convertFile(String markdown, Map<String, Long> imageAssetIds) {
        Node document = parser.parse(markdown);
        prepareDetails(document);
        Map<String, Long> refs = new LinkedHashMap<>();
        bindFileImages(document.getFirstChild(), imageAssetIds, refs);
        if (refs.size() != imageAssetIds.size()) throw new IllegalArgumentException("Markdown 存在未使用的图片资源");
        ArrayNode blocks = objectMapper.createArrayNode();
        Set<String> usedImageRefs = new HashSet<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            appendBlock(child, blocks, refs, usedImageRefs, false);
        }
        return blocks.toString();
    }

    private String fileImagePath(Image image) {
        try {
            URI uri = URI.create(image.getDestination().replace('\\', '/').replace(" ", "%20"));
            if (uri.isAbsolute() || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            String path = Path.of(uri.getPath()).normalize().toString().replace('\\', '/');
            if (path.isBlank() || path.startsWith("/")) throw new IllegalArgumentException();
            return path;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Markdown 图片须使用相对路径: " + image.getDestination());
        }
    }

    private void collectFileImageReferences(Node node, Set<String> refs) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Image image) refs.add(fileImagePath(image));
            if (current.getFirstChild() != null) collectFileImageReferences(current.getFirstChild(), refs);
        }
    }

    private void bindFileImages(Node node, Map<String, Long> imageAssetIds, Map<String, Long> refs) {
        for (Node current = node; current != null;) {
            Node next = current.getNext();
            if (current instanceof Image image) {
                String path = fileImagePath(image);
                Long assetId = imageAssetIds.get(path);
                if (assetId == null || assetId <= 0) throw new IllegalArgumentException("缺少图片资源: " + path);
                // 文件路径在转换阶段绑定资源，正文图片使用 imageAssetId 作为身份。
                String ref = "file_" + new java.util.ArrayList<>(imageAssetIds.keySet()).indexOf(path);
                refs.put(ref, assetId);
                image.setDestination(IMAGE_REF_PREFIX + ref);
            } else if (current instanceof HtmlBlock html) {
                FencedCodeBlock source = new FencedCodeBlock();
                source.setInfo("html");
                source.setLiteral(html.getLiteral());
                current.insertBefore(source);
                current.unlink();
            } else if (current.getFirstChild() != null) {
                bindFileImages(current.getFirstChild(), imageAssetIds, refs);
            }
            current = next;
        }
    }

    private void prepareDetails(Node parent) {
        for (Node current = parent.getFirstChild(); current != null;) {
            Node next = current.getNext();
            if (current instanceof HtmlBlock html) {
                var header = DETAILS_HEADER.matcher(html.getLiteral());
                if (header.find()) {
                    DetailsBlock details = new DetailsBlock(Jsoup.parseBodyFragment(header.group(1)).text());
                    current.insertBefore(details);
                    String body = html.getLiteral().substring(header.end());
                    var end = DETAILS_END.matcher(body);
                    if (end.find()) {
                        appendParsedChildren(details, body.substring(0, end.start()));
                    } else {
                        appendParsedChildren(details, body);
                        int depth = 1;
                        Node sibling = next;
                        boolean closed = false;
                        while (sibling != null) {
                            Node after = sibling.getNext();
                            if (sibling instanceof HtmlBlock fragment) {
                                var tags = DETAILS_TAG.matcher(fragment.getLiteral());
                                while (tags.find()) depth += tags.group().startsWith("</") ? -1 : 1;
                                if (depth == 0) {
                                    if (!fragment.getLiteral().trim().equalsIgnoreCase("</details>"))
                                        throw new IllegalArgumentException("details 结束标签须独占一行");
                                    sibling.unlink(); next = after; closed = true; break;
                                }
                            }
                            details.appendChild(sibling);
                            sibling = after;
                        }
                        if (!closed) throw new IllegalArgumentException("details 缺少结束标签");
                    }
                    current.unlink();
                    prepareDetails(details);
                } else if (html.getLiteral().trim().equalsIgnoreCase("</details>")) {
                    throw new IllegalArgumentException("details 结束标签缺少对应的开始标签");
                }
            } else if (current.getFirstChild() != null) {
                prepareDetails(current);
            }
            current = next;
        }
    }

    private void appendParsedChildren(Node parent, String markdown) {
        Node parsed = parser.parse(markdown);
        for (Node child = parsed.getFirstChild(); child != null;) {
            Node next = child.getNext(); parent.appendChild(child); child = next;
        }
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
            appendBlock(child, blocks, imageAssetIds, usedImageRefs, false);
        }
        if (!usedImageRefs.equals(imageAssetIds.keySet())) {
            throw new McpInvalidParamsException("Markdown 图片引用与 images 不匹配");
        }
        return blocks.toString();
    }

    /** 将任务 Markdown 转为任务编辑器使用的 BlockNote JSON，图片保留 URL。 */
    public String convertTaskDescription(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "[]";
        }
        ArrayNode blocks = objectMapper.createArrayNode();
        Set<String> usedImageRefs = new HashSet<>();
        Node document = parser.parse(markdown);
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            appendBlock(child, blocks, Map.of(), usedImageRefs, true);
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
                             Set<String> usedImageRefs, boolean taskDescription) {
        if (node instanceof DetailsBlock details) {
            ArrayNode title = objectMapper.createArrayNode().add(textNode(details.title, Map.of()));
            ObjectNode toggle = block("toggleListItem", title, Map.of());
            for (Node child = details.getFirstChild(); child != null; child = child.getNext())
                appendBlock(child, toggle.withArray("children"), imageAssetIds, usedImageRefs, taskDescription);
            blocks.add(toggle);
        } else if (node instanceof Heading heading) {
            appendInlineBlocks(heading.getFirstChild(), "heading", Map.of("level", heading.getLevel()),
                    blocks, imageAssetIds, usedImageRefs, taskDescription);
        } else if (node instanceof TableBlock table) {
            appendTable(table, blocks, imageAssetIds, usedImageRefs);
        } else if (node instanceof Paragraph paragraph) {
            appendInlineBlocks(paragraph.getFirstChild(), "paragraph", Map.of(),
                    blocks, imageAssetIds, usedImageRefs, taskDescription);
        } else if (node instanceof BulletList list) {
            appendList(list, "bulletListItem", blocks, imageAssetIds, usedImageRefs, taskDescription);
        } else if (node instanceof OrderedList list) {
            appendList(list, "numberedListItem", blocks, imageAssetIds, usedImageRefs, taskDescription);
        } else if (node instanceof FencedCodeBlock code) {
            blocks.add(codeBlock(code.getLiteral(), code.getInfo()));
        } else if (node instanceof IndentedCodeBlock code) {
            blocks.add(codeBlock(code.getLiteral(), ""));
        } else if (node instanceof BlockQuote quote) {
            for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph paragraph) {
                    appendInlineBlocks(paragraph.getFirstChild(), "quote", Map.of(),
                            blocks, imageAssetIds, usedImageRefs, taskDescription);
                } else {
                    appendBlock(child, blocks, imageAssetIds, usedImageRefs, taskDescription);
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
                            objectMapper.createArrayNode(), imageAssetIds, usedImageRefs, false, false);
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
                            Map<String, Long> imageAssetIds, Set<String> usedImageRefs,
                            boolean taskDescription) {
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem listItem)) {
                continue;
            }
            Node first = listItem.getFirstChild();
            if (first instanceof Paragraph paragraph) {
                ArrayNode segments = objectMapper.createArrayNode();
                appendInlineBlocks(paragraph.getFirstChild(), type, Map.of(), segments,
                        imageAssetIds, usedImageRefs, taskDescription);

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
                    if (!"documentImage".equals(segment.path("type").asText())
                            && !(taskDescription && "image".equals(segment.path("type").asText()))) {
                        segment.put("type", "paragraph");
                    }
                    children.add(segment);
                }
                for (Node nested = first.getNext(); nested != null; nested = nested.getNext()) {
                    appendBlock(nested, children, imageAssetIds, usedImageRefs, taskDescription);
                }
                blocks.add(listBlock);
            }
        }
    }

    private void appendInlineBlocks(Node node, String type, Map<String, Integer> extraProps,
                                   ArrayNode blocks, Map<String, Long> imageAssetIds,
                                   Set<String> usedImageRefs, boolean taskDescription) {
        ArrayNode content = objectMapper.createArrayNode();
        appendInline(node, content, Map.of(), type, extraProps, blocks, imageAssetIds,
                usedImageRefs, false, taskDescription);
        flushInlineBlock(type, extraProps, content, blocks);
    }

    private void appendInline(Node node, ArrayNode content, Map<String, Boolean> styles,
                              String blockType, Map<String, Integer> extraProps, ArrayNode blocks,
                              Map<String, Long> imageAssetIds, Set<String> usedImageRefs,
                              boolean insideLink, boolean taskDescription) {
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Text text) {
                content.add(textNode(text.getLiteral(), styles));
            } else if (current instanceof Code code) {
                content.add(textNode(code.getLiteral(), Map.of("code", true)));
            } else if (current instanceof Emphasis emphasis) {
                appendInline(emphasis.getFirstChild(), content, merge(styles, "italic"), blockType,
                        extraProps, blocks, imageAssetIds, usedImageRefs, insideLink, taskDescription);
            } else if (current instanceof StrongEmphasis strong) {
                appendInline(strong.getFirstChild(), content, merge(styles, "bold"), blockType,
                        extraProps, blocks, imageAssetIds, usedImageRefs, insideLink, taskDescription);
            } else if (current instanceof Link link) {
                if (containsImage(link)) {
                    throw new McpInvalidParamsException("图片不能嵌入 Markdown 链接");
                }
                ObjectNode linkNode = objectMapper.createObjectNode();
                linkNode.put("type", "link");
                linkNode.put("href", link.getDestination());
                ArrayNode linkContent = linkNode.putArray("content");
                appendInline(link.getFirstChild(), linkContent, styles, blockType,
                        extraProps, blocks, imageAssetIds, usedImageRefs, true, taskDescription);
                content.add(linkNode);
            } else if (current instanceof HardLineBreak) {
                content.add(textNode("\n", styles));
            } else if (current instanceof HtmlInline html) {
                content.add(textNode(html.getLiteral(), styles));
            } else if (current instanceof Image image) {
                if (insideLink) {
                    throw new McpInvalidParamsException("图片不能嵌入 Markdown 链接");
                }
                if (taskDescription) {
                    flushInlineBlock(blockType, extraProps, content, blocks);
                    blocks.add(taskImage(image.getDestination(), imageCaption(image)));
                    continue;
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
                        blocks, imageAssetIds, usedImageRefs, insideLink, taskDescription);
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

    private ObjectNode taskImage(String url, String caption) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new McpInvalidParamsException("任务描述图片须使用 HTTP 或 HTTPS URL");
        }
        ObjectNode block = block("image", objectMapper.createArrayNode(), Map.of());
        ObjectNode props = block.with("props");
        props.put("url", url);
        props.put("caption", caption);
        props.put("showPreview", true);
        props.put("previewWidth", 512);
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
