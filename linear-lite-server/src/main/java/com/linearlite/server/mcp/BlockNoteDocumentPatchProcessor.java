package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Applies focused edits to a BlockNote document tree without rebuilding untouched JSON nodes. */
final class BlockNoteDocumentPatchProcessor {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_JSON_BYTES = 5 * 1024 * 1024;
    private static final String ATOMIC_INLINE = "\uFFFC";
    private static final Set<String> BLOCK_TYPES = Set.of(
            "paragraph", "heading", "bulletListItem", "numberedListItem", "checkListItem",
            "quote", "codeBlock", "table", "documentImage", "divider");
    private static final Set<String> COMMON_PROPS = Set.of("backgroundColor", "textColor", "textAlignment");
    private static final Set<String> INLINE_BLOCK_TYPES = Set.of(
            "paragraph", "heading", "bulletListItem", "numberedListItem", "checkListItem", "quote", "codeBlock");

    private final ObjectMapper mapper;

    BlockNoteDocumentPatchProcessor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record Result(ArrayNode blocks, List<String> createdBlockIds) {}

    Result apply(ArrayNode original, ArrayNode operations, Map<String, Long> imageReferences) {
        if (original == null || operations == null) throw invalid("blocks 和 operations 必须为数组");
        ArrayNode working = original.deepCopy();
        validateTree(working);
        validateSize(working);
        validateImageAssets(working, null, null);
        List<String> createdIds = new ArrayList<>();
        Set<String> usedImageRefs = new HashSet<>();
        for (int index = 0; index < operations.size(); index++) {
            JsonNode operation = operations.get(index);
            try {
                applyOne(working, object(operation, "operation"), createdIds);
                validateImageAssets(working, imageReferences, usedImageRefs);
                validateTree(working);
                validateSize(working);
            } catch (McpInvalidParamsException e) {
                throw new McpDocumentPatchOperationException(index, e.getMessage(), e);
            }
        }
        if (imageReferences != null && !usedImageRefs.equals(imageReferences.keySet()))
            throw invalid("images 与 documentImage.imageRef 不匹配");
        return new Result(working, List.copyOf(createdIds));
    }

    private void applyOne(ArrayNode root, ObjectNode op, List<String> createdIds) {
        String kind = requiredText(op, "op");
        validateOperationFields(op, kind);
        switch (kind) {
            case "update_block_props" -> updateProps(root, op);
            case "replace_inline_range" -> replaceInline(root, op);
            case "insert_block" -> insert(root, op, createdIds);
            case "move_block" -> move(root, op);
            case "delete_block" -> delete(root, op);
            case "insert_table_row" -> insertTableRow(root, op);
            case "update_table_cell" -> updateCell(root, op);
            case "update_table_column_widths" -> updateColumnWidths(root, op);
            default -> throw invalid("不支持的操作: " + kind);
        }
    }

    private void validateOperationFields(ObjectNode op, String kind) {
        Set<String> allowed = switch (kind) {
            case "update_block_props" -> Set.of("op", "blockId", "props");
            case "replace_inline_range" -> Set.of("op", "blockId", "start", "end", "expectedText", "content");
            case "insert_block" -> Set.of("op", "parentId", "beforeBlockId", "afterBlockId", "block");
            case "move_block" -> Set.of("op", "blockId", "parentId", "beforeBlockId", "afterBlockId");
            case "delete_block" -> Set.of("op", "blockId");
            case "insert_table_row" -> Set.of("op", "tableBlockId", "rowIndex", "row");
            case "update_table_cell" -> Set.of("op", "tableBlockId", "rowIndex", "columnIndex", "content");
            case "update_table_column_widths" -> Set.of("op", "tableBlockId", "columnWidths");
            default -> throw invalid("不支持的操作: " + kind);
        };
        Iterator<String> fields = op.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) throw invalid("操作字段不支持: " + field);
        }
    }

    private void updateProps(ArrayNode root, ObjectNode op) {
        ObjectNode block = findBlock(root, requiredText(op, "blockId"));
        if (!BLOCK_TYPES.contains(text(block.path("type")))) throw invalid("目标 block 类型不可编辑");
        ObjectNode patch = object(op.get("props"), "props");
        Set<String> allowed = propsFor(text(block.path("type")));
        JsonNode currentProps = block.get("props");
        ObjectNode props;
        if (currentProps == null || currentProps.isNull()) {
            props = mapper.createObjectNode();
            block.set("props", props);
        } else {
            props = object(currentProps, "block.props");
        }
        Iterator<String> fields = patch.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            JsonNode value = patch.get(name);
            if (!allowed.contains(name)) throw invalid("props 字段不允许更新: " + name);
            if (value.isNull() || value.isContainerNode()) throw invalid("props 值须为非空标量: " + name);
            if (!validPropValue(text(block.path("type")), name, value)) throw invalid("props 值类型或范围无效: " + name);
            props.set(name, value.deepCopy());
        }
    }

    private boolean validPropValue(String type, String name, JsonNode value) {
        return switch (name) {
            case "level" -> "heading".equals(type) && value.isIntegralNumber() && value.asInt() >= 1 && value.asInt() <= 3;
            case "checked" -> "checkListItem".equals(type) && value.isBoolean();
            case "start" -> "numberedListItem".equals(type) && value.isIntegralNumber() && value.asInt() > 0;
            case "language", "caption", "backgroundColor", "textColor", "textAlignment" -> value.isTextual();
            default -> false;
        };
    }

    private void replaceInline(ArrayNode root, ObjectNode op) {
        ObjectNode block = findBlock(root, requiredText(op, "blockId"));
        if (!INLINE_BLOCK_TYPES.contains(text(block.path("type")))) throw invalid("目标 block 不支持 inline 编辑");
        JsonNode content = block.get("content");
        if (!(content instanceof ArrayNode inline)) throw invalid("目标 block 不含 inline content");
        int start = requiredInt(op, "start");
        int end = requiredInt(op, "end");
        String expected = requiredString(op, "expectedText");
        ArrayNode replacement = array(op.get("content"), "content");
        String flat = flatten(inline);
        if (start < 0 || end < start || end > flat.length()) throw invalid("inline 范围越界");
        ensureBoundary(flat, start);
        ensureBoundary(flat, end);
        if (!flat.substring(start, end).equals(expected)) throw invalid("expectedText 与当前内容不匹配");
        validateInlineArray(replacement);
        ArrayNode next = mapper.createArrayNode();
        append(next, slice(inline, 0, start, 0));
        append(next, replacement.deepCopy());
        append(next, slice(inline, end, flat.length(), 0));
        block.set("content", next);
    }

    private void insert(ArrayNode root, ObjectNode op, List<String> createdIds) {
        ObjectNode block = object(op.get("block"), "block").deepCopy();
        if (block.has("id") && !block.get("id").isNull() && !block.get("id").asText().isBlank())
            throw invalid("insert_block 不接受已有 id");
        String type = requiredText(block, "type");
        if (!BLOCK_TYPES.contains(type)) throw invalid("不支持插入的 block 类型: " + type);
        if (!block.has("content") || !block.has("children") || !(block.get("children") instanceof ArrayNode))
            throw invalid("block 必须包含 content 与 children");
        String parentId = optionalText(op, "parentId");
        String beforeId = optionalText(op, "beforeBlockId");
        String afterId = optionalText(op, "afterBlockId");
        if (beforeId != null && afterId != null) throw invalid("beforeBlockId 与 afterBlockId 互斥");
        validateSupportedSubtree(block);
        ArrayNode siblings = parentId == null ? root : children(findBlock(root, parentId));
        int position = anchorPosition(root, siblings, beforeId, afterId);
        String id = UUID.randomUUID().toString();
        block.put("id", id);
        siblings.insert(position, block);
        createdIds.add(id);
    }

    private void move(ArrayNode root, ObjectNode op) {
        String blockId = requiredText(op, "blockId");
        ObjectNode block = findBlock(root, blockId);
        String parentId = optionalText(op, "parentId");
        String beforeId = optionalText(op, "beforeBlockId");
        String afterId = optionalText(op, "afterBlockId");
        if (beforeId != null && afterId != null) throw invalid("beforeBlockId 与 afterBlockId 互斥");
        if (blockId.equals(parentId)) throw invalid("不能将 block 移入自身子树");
        if (parentId != null && isDescendant(block, parentId)) throw invalid("不能将 block 移入自身子树");
        if (beforeId != null && (beforeId.equals(blockId) || isDescendant(block, beforeId)))
            throw invalid("目标 anchor 位于待移动子树内");
        if (afterId != null && (afterId.equals(blockId) || isDescendant(block, afterId)))
            throw invalid("目标 anchor 位于待移动子树内");
        ArrayNode oldSiblings = findSiblings(root, blockId);
        int oldIndex = indexOf(oldSiblings, blockId);
        oldSiblings.remove(oldIndex);
        ArrayNode siblings = parentId == null ? root : children(findBlock(root, parentId));
        int position = anchorPosition(root, siblings, beforeId, afterId);
        // Same-parent moves resolve the destination after removal, which is the desired final index.
        siblings.insert(position, block);
    }

    private void delete(ArrayNode root, ObjectNode op) {
        String id = requiredText(op, "blockId");
        ArrayNode siblings = findSiblings(root, id);
        siblings.remove(indexOf(siblings, id));
    }

    private void updateCell(ArrayNode root, ObjectNode op) {
        ObjectNode table = findBlock(root, requiredText(op, "tableBlockId"));
        if (!"table".equals(text(table.get("type")))) throw invalid("tableBlockId 不是 table");
        ObjectNode tableContent = object(table.get("content"), "table.content");
        ArrayNode rows = array(tableContent.get("rows"), "table.rows");
        int rowIndex = requiredInt(op, "rowIndex");
        int columnIndex = requiredInt(op, "columnIndex");
        if (rowIndex < 0 || rowIndex >= rows.size()) throw invalid("rowIndex 越界");
        ArrayNode cells = array(rows.get(rowIndex).get("cells"), "row.cells");
        if (columnIndex < 0 || columnIndex >= cells.size()) throw invalid("columnIndex 越界");
        ObjectNode cell = object(cells.get(columnIndex), "cell");
        JsonNode props = cell.path("props");
        if (!unitSpan(props.get("rowspan")) || !unitSpan(props.get("colspan")))
            throw invalid("不支持编辑合并单元格");
        ArrayNode replacement = array(op.get("content"), "content");
        validateInlineArray(replacement);
        cell.set("content", replacement.deepCopy());
    }

    private void insertTableRow(ArrayNode root, ObjectNode op) {
        ObjectNode table = findBlock(root, requiredText(op, "tableBlockId"));
        if (!"table".equals(text(table.get("type")))) throw invalid("tableBlockId 不是 table");
        ObjectNode tableContent = object(table.get("content"), "table.content");
        ArrayNode rows = array(tableContent.get("rows"), "table.rows");
        int rowIndex = requiredInt(op, "rowIndex");
        if (rowIndex < 0 || rowIndex > rows.size()) throw invalid("rowIndex 须在 0 到当前行数之间");

        int columnCount;
        if (!rows.isEmpty()) {
            columnCount = array(rows.get(0).get("cells"), "table row.cells").size();
            for (JsonNode existingRow : rows) validateUnmergedRow(object(existingRow, "table row"), columnCount);
        } else {
            JsonNode widthsNode = tableContent.get("columnWidths");
            if (!(widthsNode instanceof ArrayNode widths) || widths.isEmpty())
                throw invalid("空表格须提供 columnWidths 以确定列数");
            columnCount = widths.size();
        }

        ObjectNode row = object(op.get("row"), "row").deepCopy();
        validateUnmergedRow(row, columnCount);
        rows.insert(rowIndex, row);
    }

    private void validateUnmergedRow(ObjectNode row, int expectedColumns) {
        ArrayNode cells = array(row.get("cells"), "row.cells");
        if (cells.size() != expectedColumns) throw invalid("row.cells 数量须与表格列数相同");
        for (JsonNode cellNode : cells) {
            ObjectNode cell = object(cellNode, "table cell");
            if (!"tableCell".equals(text(cell.get("type")))) throw invalid("row.cells 元素 type 必须是 tableCell");
            JsonNode props = cell.get("props");
            if (props != null && !props.isObject()) throw invalid("table cell.props 必须为对象");
            if (!unitSpan(props == null ? null : props.get("rowspan"))
                    || !unitSpan(props == null ? null : props.get("colspan")))
                throw invalid("不支持向含合并单元格的表格插入行");
            validateInlineArray(array(cell.get("content"), "table cell.content"));
        }
    }

    private void updateColumnWidths(ArrayNode root, ObjectNode op) {
        ObjectNode table = findBlock(root, requiredText(op, "tableBlockId"));
        if (!"table".equals(text(table.get("type")))) throw invalid("tableBlockId 不是 table");
        ObjectNode tableContent = object(table.get("content"), "table.content");
        ArrayNode widths = array(op.get("columnWidths"), "columnWidths");
        ArrayNode rows = array(tableContent.get("rows"), "table.rows");
        int columns = rows.isEmpty() ? 0 : array(rows.get(0).get("cells"), "row.cells").size();
        if (widths.size() != columns) throw invalid("columnWidths 长度须与表格列数相同");
        for (JsonNode width : widths) if (!width.isNumber() || width.asDouble() <= 0) throw invalid("列宽须为正数");
        tableContent.set("columnWidths", widths.deepCopy());
    }

    private int anchorPosition(ArrayNode root, ArrayNode siblings, String beforeId, String afterId) {
        if (beforeId != null) {
            if (findSiblings(root, beforeId) != siblings) throw invalid("beforeBlockId 必须是目标 parent 的直接子项");
            return indexOf(siblings, beforeId);
        }
        if (afterId != null) {
            if (findSiblings(root, afterId) != siblings) throw invalid("afterBlockId 必须是目标 parent 的直接子项");
            return indexOf(siblings, afterId) + 1;
        }
        return siblings.size();
    }

    private ArrayNode slice(ArrayNode source, int from, int to, int base) {
        ArrayNode out = mapper.createArrayNode();
        int cursor = base;
        for (JsonNode node : source) {
            String type = text(node.path("type"));
            if ("text".equals(type)) {
                String value = text(node.path("text"));
                int left = Math.max(from, cursor) - cursor;
                int right = Math.min(to, cursor + value.length()) - cursor;
                if (right > left) {
                    ObjectNode copy = ((ObjectNode) node).deepCopy();
                    copy.put("text", value.substring(left, right));
                    out.add(copy);
                }
                cursor += value.length();
            } else if ("link".equals(type)) {
                ArrayNode children = array(node.get("content"), "link.content");
                int length = flatten(children).length();
                int left = Math.max(from, cursor);
                int right = Math.min(to, cursor + length);
                if (right > left) {
                    ObjectNode copy = ((ObjectNode) node).deepCopy();
                    copy.set("content", slice(children, left, right, cursor));
                    out.add(copy);
                }
                cursor += length;
            } else {
                if (from <= cursor && cursor + 1 <= to) out.add(node.deepCopy());
                cursor++;
            }
        }
        return out;
    }

    private String flatten(JsonNode nodes) {
        StringBuilder out = new StringBuilder();
        for (JsonNode node : nodes) {
            String type = text(node.path("type"));
            if ("text".equals(type)) out.append(text(node.path("text")));
            else if ("link".equals(type) && node.path("content").isArray()) out.append(flatten(node.path("content")));
            else out.append(ATOMIC_INLINE);
        }
        return out.toString();
    }

    private void ensureBoundary(String text, int offset) {
        if (offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)))
            throw invalid("inline 范围不能切开 UTF-16 surrogate pair");
    }

    private void validateInlineArray(ArrayNode content) {
        for (JsonNode node : content) {
            if (!node.isObject()) throw invalid("inline content 元素须为对象");
            String type = text(node.path("type"));
            if ("text".equals(type) && !node.path("text").isTextual()) throw invalid("text inline 必须包含字符串 text");
            if ("link".equals(type)) validateInlineArray(array(node.get("content"), "link.content"));
        }
    }

    private void validateTree(ArrayNode root) {
        Set<String> ids = new HashSet<>();
        validateBlocks(root, ids, 1);
    }

    private Set<String> propsFor(String type) {
        Set<String> allowed = switch (type) {
            case "paragraph", "bulletListItem", "quote" -> new HashSet<>(COMMON_PROPS);
            case "heading" -> new HashSet<>(COMMON_PROPS);
            case "numberedListItem" -> new HashSet<>(COMMON_PROPS);
            case "checkListItem" -> new HashSet<>(COMMON_PROPS);
            case "codeBlock" -> new HashSet<>(COMMON_PROPS);
            case "documentImage" -> new HashSet<>(Set.of("caption"));
            default -> new HashSet<>();
        };
        switch (type) {
            case "heading" -> allowed.add("level");
            case "numberedListItem" -> allowed.add("start");
            case "checkListItem" -> allowed.add("checked");
            case "codeBlock" -> allowed.add("language");
            default -> { }
        }
        return allowed;
    }

    private void validateSupportedSubtree(ObjectNode block) {
        String type = requiredText(block, "type");
        if (!BLOCK_TYPES.contains(type)) throw invalid("不支持插入的 block 类型: " + type);
        JsonNode props = block.get("props");
        if (props != null && !props.isObject()) throw invalid("block.props 必须为对象");
        if ("documentImage".equals(type)) {
            JsonNode imageRef = props == null ? null : props.get("imageRef");
            if (imageRef == null || !imageRef.isTextual() || imageRef.asText().isBlank()
                    || props.has("imageAssetId")) {
                throw invalid("插入 documentImage 必须通过 props.imageRef 提供图片资源");
            }
        }
        JsonNode content = block.get("content");
        if ("table".equals(type)) validateTableContent(object(content, "table.content"));
        else validateInlineArray(array(content, "block.content"));
        JsonNode children = block.get("children");
        if (!(children instanceof ArrayNode array)) throw invalid("block.children 必须为数组");
        for (JsonNode child : array) validateSupportedSubtree(object(child, "child block"));
    }

    private void validateTableContent(ObjectNode content) {
        if (!"tableContent".equals(text(content.path("type")))) throw invalid("table.content.type 必须是 tableContent");
        ArrayNode rows = array(content.get("rows"), "table.content.rows");
        int expectedColumns = -1;
        for (JsonNode rowNode : rows) {
            ObjectNode row = object(rowNode, "table row");
            ArrayNode cells = array(row.get("cells"), "table row.cells");
            if (expectedColumns < 0) expectedColumns = cells.size();
            if (cells.size() != expectedColumns) throw invalid("table 每行的列数必须相同");
            for (JsonNode cellNode : cells) {
                ObjectNode cell = object(cellNode, "table cell");
                JsonNode cellProps = cell.get("props");
                if (cellProps != null && !cellProps.isObject()) throw invalid("table cell.props 必须为对象");
                validateInlineArray(array(cell.get("content"), "table cell.content"));
            }
        }
        JsonNode widthsNode = content.get("columnWidths");
        if (widthsNode != null && !widthsNode.isNull()) {
            ArrayNode widths = array(widthsNode, "table.content.columnWidths");
            if (expectedColumns >= 0 && widths.size() != expectedColumns) throw invalid("table columnWidths 列数不匹配");
            for (JsonNode width : widths)
                if (!width.isNumber() || width.asDouble() <= 0) throw invalid("table 列宽须为正数");
        }
    }

    private void validateBlocks(ArrayNode blocks, Set<String> ids, int depth) {
        if (depth > MAX_DEPTH) throw invalid("block 树深度超过 32");
        for (JsonNode node : blocks) {
            if (!node.isObject()) throw invalid("block 必须为对象");
            String id = requiredText((ObjectNode) node, "id");
            if (!ids.add(id)) throw invalid("block id 重复: " + id);
            if ("documentImage".equals(text(node.path("type")))) {
                JsonNode assetId = node.path("props").path("imageAssetId");
                if (!assetId.isIntegralNumber() || !assetId.canConvertToLong() || assetId.asLong() <= 0)
                    throw invalid("documentImage.props.imageAssetId 必须为正整数");
                if (node.path("props").has("url")) throw invalid("documentImage 不得保存资源 URL");
                if (node.path("props").has("imageRef")) throw invalid("documentImage 不得保留 imageRef");
            }
            JsonNode children = node.get("children");
            if (children != null) validateBlocks(array(children, "children"), ids, depth + 1);
        }
    }

    private void validateImageAssets(ArrayNode root, Map<String, Long> imageReferences, Set<String> usedRefs) {
        for (JsonNode node : root) {
            String type = text(node.path("type"));
            JsonNode propsNode = node.get("props");
            if ("documentImage".equals(type)) {
                if (!(propsNode instanceof ObjectNode props)) throw invalid("documentImage.props 必须为对象");
                JsonNode ref = props.get("imageRef");
                if (ref != null) {
                    if (!ref.isTextual() || ref.asText().isBlank() || props.has("imageAssetId"))
                        throw invalid("documentImage.imageRef 无效");
                    Long assetId = imageReferences == null ? null : imageReferences.get(ref.asText());
                    if (assetId == null || assetId <= 0) throw invalid("缺少有效图片资源: " + ref.asText());
                    props.remove("imageRef");
                    props.put("imageAssetId", assetId);
                    if (usedRefs != null) usedRefs.add(ref.asText());
                }
            } else if (propsNode != null && propsNode.has("imageRef")) {
                throw invalid("imageRef 仅支持 documentImage.props");
            }
            JsonNode children = node.get("children");
            if (children instanceof ArrayNode array) validateImageAssets(array, imageReferences, usedRefs);
        }
    }

    private void validateSize(ArrayNode root) {
        try {
            if (mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
                throw invalid("BlockNote JSON 超过 5 MiB");
        } catch (McpInvalidParamsException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("无法序列化 BlockNote JSON");
        }
    }

    private ObjectNode findBlock(ArrayNode root, String id) {
        for (JsonNode node : root) {
            if (id.equals(text(node.path("id")))) return (ObjectNode) node;
            JsonNode children = node.get("children");
            if (children instanceof ArrayNode array) {
                ObjectNode found = findBlockOrNull(array, id);
                if (found != null) return found;
            }
        }
        throw invalid("block 不存在: " + id);
    }

    private ObjectNode findBlockOrNull(ArrayNode root, String id) {
        for (JsonNode node : root) {
            if (id.equals(text(node.path("id")))) return (ObjectNode) node;
            if (node.path("children").isArray()) {
                ObjectNode found = findBlockOrNull((ArrayNode) node.path("children"), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private ArrayNode findSiblings(ArrayNode root, String id) {
        for (JsonNode node : root) {
            if (id.equals(text(node.path("id")))) return root;
            if (node.path("children").isArray()) {
                ArrayNode found = findSiblingsOrNull((ArrayNode) node.path("children"), id);
                if (found != null) return found;
            }
        }
        throw invalid("block 不存在: " + id);
    }

    private ArrayNode findSiblingsOrNull(ArrayNode root, String id) {
        for (JsonNode node : root) {
            if (id.equals(text(node.path("id")))) return root;
            if (node.path("children").isArray()) {
                ArrayNode found = findSiblingsOrNull((ArrayNode) node.path("children"), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isDescendant(ObjectNode block, String candidateId) {
        JsonNode children = block.path("children");
        return children.isArray() && containsId((ArrayNode) children, candidateId);
    }

    private boolean containsId(ArrayNode nodes, String id) {
        for (JsonNode node : nodes) {
            if (id.equals(text(node.path("id"))) || node.path("children").isArray()
                    && containsId((ArrayNode) node.path("children"), id)) return true;
        }
        return false;
    }

    private int indexOf(ArrayNode siblings, String id) {
        for (int i = 0; i < siblings.size(); i++) if (id.equals(text(siblings.get(i).path("id")))) return i;
        throw invalid("block 不存在: " + id);
    }

    private ArrayNode children(ObjectNode block) {
        JsonNode children = block.get("children");
        if (!(children instanceof ArrayNode array)) throw invalid("parent block 不含 children 数组");
        return array;
    }

    private void append(ArrayNode target, ArrayNode source) {
        for (JsonNode node : source) target.add(node);
    }

    private ObjectNode object(JsonNode value, String name) {
        if (!(value instanceof ObjectNode object)) throw invalid(name + " 必须为对象");
        return object;
    }

    private ArrayNode array(JsonNode value, String name) {
        if (!(value instanceof ArrayNode array)) throw invalid(name + " 必须为数组");
        return array;
    }

    private String requiredText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid(field + " 必须为非空字符串");
        return value.asText();
    }

    private String requiredString(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw invalid(field + " 必须为字符串");
        return value.asText();
    }

    private String optionalText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) throw invalid(field + " 须为非空字符串或 null");
        return value.asText();
    }

    private int requiredInt(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber()) throw invalid(field + " 必须为整数");
        return value.asInt();
    }

    private boolean unitSpan(JsonNode span) {
        return span == null || span.isNull() || span.isIntegralNumber() && span.asInt() == 1;
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private McpInvalidParamsException invalid(String message) {
        return new McpInvalidParamsException(message);
    }
}
