package com.linearlite.server.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Generates DBX-only SQL batches and verifies Node round trips; this tool never opens a database connection. */
public final class CollaborationStateMigrationTool {
    private static final int SQL_BATCH_ROWS = 50;
    private static final int SQL_BATCH_MAX_BYTES = 1_500_000;
    private static final int LARGE_STATE_BASE64_PART_CHARS = 1_000_000;
    private static final int CHUNK_BYTES = 2_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CollaborationStateMigrationTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) usage();
        switch (args[0]) {
            case "generate" -> generate(args);
            case "verify" -> verify(args);
            default -> usage();
        }
    }

    private static void generate(String[] args) throws Exception {
        if (args.length != 6) usage();
        int expectedDocumentCount = Integer.parseInt(args[1]);
        Path manifestPath = Path.of(args[2]);
        Path chunksPath = Path.of(args[3]);
        Path outputDir = Path.of(args[4]);
        String nodeUrl = args[5];
        RestClient node = nodeClient(nodeUrl);
        Files.createDirectories(outputDir);
        Map<Long, Manifest> manifests = readManifest(manifestPath);
        Map<Long, TreeMap<Integer, String>> chunks = readChunks(chunksPath);
        if (manifests.size() != expectedDocumentCount || !manifests.keySet().equals(chunks.keySet())) {
            throw new IllegalStateException("DBX 导出缺少文档或正文分片");
        }

        List<Manifest> ordered = manifests.values().stream().sorted(java.util.Comparator.comparing(Manifest::documentId)).toList();
        List<String> insertRows = new ArrayList<>(SQL_BATCH_ROWS);
        int batch = 1;
        int convertedImages = 0;
        int imagePlaceholders = 0;
        int imageAltTextDefaults = 0;
        int documentImageDefaultRemovals = 0;
        int emptyTextRunsRemoved = 0;
        try (BufferedWriter epochWriter = Files.newBufferedWriter(outputDir.resolve("epochs.tsv"), StandardCharsets.UTF_8)) {
            epochWriter.write("documentId\tversion\tepoch\tcontentFormat\tsourceContentSha256"
                    + "\tmigratedContentSha256\timageConversionCount\timagePlaceholderCount"
                    + "\tdefaultAltTextCount\tdocumentImageDefaultRemovalCount\temptyInlineTextRemovalCount"
                    + "\tstateByteLength\tstateSha256\n");
            for (Manifest manifest : ordered) {
                byte[] contentBytes = assemble(manifest, chunks.get(manifest.documentId()));
                String sourceContent = decodeUtf8(contentBytes, manifest.documentId());
                PreparedContent prepared = prepareContent(manifest.documentId(), manifest.contentFormat(), sourceContent);
                String content = prepared.content();
                convertedImages += prepared.imageConversionCount();
                imagePlaceholders += prepared.imagePlaceholderCount();
                imageAltTextDefaults += prepared.defaultAltTextCount();
                documentImageDefaultRemovals += prepared.documentImageDefaultRemovalCount();
                emptyTextRunsRemoved += prepared.emptyInlineTextRemovalCount();
                EncodeResponse encoded = node.post().uri("/internal/collaboration/encode")
                        .header(CollaborationNodeClientHeader.VALUE, requiredInternalKey())
                        .body(new EncodeRequest(manifest.contentFormat(), content))
                        .retrieve().body(EncodeResponse.class);
                if (encoded == null || blank(encoded.stateBase64())) {
                    throw new IllegalStateException("Node 未返回 Yjs state，documentId=" + manifest.documentId());
                }
                byte[] stateBinary;
                try {
                    stateBinary = Base64.getDecoder().decode(encoded.stateBase64());
                } catch (IllegalArgumentException failure) {
                    throw new IllegalStateException("Node 返回的 Yjs state 编码无效，documentId="
                            + manifest.documentId(), failure);
                }
                String stateSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(stateBinary));
                if (stateBinary.length == 0 || encoded.stateSha256() == null
                        || !stateSha256.equalsIgnoreCase(encoded.stateSha256())) {
                    throw new IllegalStateException("Node state SHA-256 不一致，documentId="
                            + manifest.documentId());
                }
                if (encoded.content() == null || encoded.contentBytes() == null
                        || encoded.contentBytes() != encoded.content().getBytes(StandardCharsets.UTF_8).length) {
                    throw new IllegalStateException("Node 正文 UTF-8 字节数不一致，documentId="
                            + manifest.documentId());
                }
                if (!sameContent(manifest.contentFormat(), content, encoded.content())) {
                    throw new IllegalStateException("Node 编码回读不一致，documentId=" + manifest.documentId()
                            + "，path=" + firstDifference(normalizeBlockNoteForComparison(JSON.readTree(content)),
                            normalizeBlockNoteForComparison(JSON.readTree(encoded.content())), "$"));
                }
                String epoch = UUID.randomUUID().toString();
                String insertRow = "(" + manifest.documentId() + ", '" + epoch + "', 1, FROM_BASE64('"
                        + encoded.stateBase64() + "'))";
                int addedBytes = insertRow.getBytes(StandardCharsets.UTF_8).length + 2;
                int currentBytes = insertRows.stream().mapToInt(row -> row.getBytes(StandardCharsets.UTF_8).length + 2)
                        .sum();
                if (!insertRows.isEmpty() && (addedBytes + 96 > SQL_BATCH_MAX_BYTES
                        || insertRows.size() >= SQL_BATCH_ROWS
                        || currentBytes + addedBytes + 96 > SQL_BATCH_MAX_BYTES)) {
                    writeBatch(outputDir, batch++, insertRows);
                    insertRows.clear();
                }
                if (addedBytes + 96 > SQL_BATCH_MAX_BYTES) {
                    writeLargeState(outputDir, manifest.documentId(), epoch, encoded.stateBase64());
                } else {
                    insertRows.add(insertRow);
                }
                epochWriter.write(manifest.documentId() + "\t" + manifest.version() + "\t" + epoch + "\t"
                        + manifest.contentFormat() + "\t" + manifest.contentSha256() + "\t"
                        + prepared.contentSha256() + "\t" + prepared.imageConversionCount() + "\t"
                        + prepared.imagePlaceholderCount() + "\t" + prepared.defaultAltTextCount() + "\t"
                        + prepared.documentImageDefaultRemovalCount() + "\t"
                        + prepared.emptyInlineTextRemovalCount() + "\t"
                        + stateBinary.length + "\t" + stateSha256 + "\n");
                if (insertRows.size() == SQL_BATCH_ROWS) {
                    writeBatch(outputDir, batch++, insertRows);
                    insertRows.clear();
                }
            }
        }
        if (!insertRows.isEmpty()) writeBatch(outputDir, batch, insertRows);
        System.out.println("生成与 Node 编码回读通过：文档数=" + ordered.size() + "，批次=" + batch
                + "，旧图片转换数=" + convertedImages + "，图片占位数=" + imagePlaceholders
                + "，图片空 altText 默认补齐数=" + imageAltTextDefaults
                + "，图片默认字段清理数=" + documentImageDefaultRemovals
                + "，空 inline 文本节点清理数=" + emptyTextRunsRemoved
                + "。此工具未连接数据库；只能由 DBX 执行生成的 INSERT。");
    }

    private static void verify(String[] args) throws Exception {
        if (args.length != 6) usage();
        int expectedDocumentCount = Integer.parseInt(args[1]);
        Path manifestPath = Path.of(args[2]);
        Path chunksPath = Path.of(args[3]);
        Path epochsPath = Path.of(args[4]);
        RestClient node = nodeClient(args[5]);
        Map<Long, Manifest> manifests = readManifest(manifestPath);
        Map<Long, TreeMap<Integer, String>> chunks = readChunks(chunksPath);
        Map<Long, Epoch> epochs = readEpochs(epochsPath);
        if (manifests.size() != expectedDocumentCount || !manifests.keySet().equals(chunks.keySet())
                || !manifests.keySet().equals(epochs.keySet())) {
            throw new IllegalStateException("校验输入中的文档集合不一致");
        }
        int verified = 0;
        for (Manifest manifest : manifests.values().stream()
                .sorted(java.util.Comparator.comparing(Manifest::documentId)).toList()) {
            String sourceContent = decodeUtf8(assemble(manifest, chunks.get(manifest.documentId())), manifest.documentId());
            PreparedContent prepared = prepareContent(manifest.documentId(), manifest.contentFormat(), sourceContent);
            String content = prepared.content();
            Epoch epoch = epochs.get(manifest.documentId());
            SnapshotResponse snapshot = node.get()
                    .uri("/internal/collaboration/documents/{id}/snapshot?epoch={epoch}&flush=false",
                            manifest.documentId(), epoch.epoch())
                    .header(CollaborationNodeClientHeader.VALUE, requiredInternalKey())
                    .retrieve().body(SnapshotResponse.class);
            if (snapshot == null || !epoch.epoch().equals(snapshot.epoch())
                    || !manifest.contentFormat().equals(snapshot.contentFormat())
                    || !manifest.version().equals(snapshot.version())
                    || !manifest.contentSha256().equalsIgnoreCase(epoch.sourceContentSha256())
                    || !prepared.contentSha256().equalsIgnoreCase(epoch.migratedContentSha256())
                    || prepared.imageConversionCount() != epoch.imageConversionCount()
                    || prepared.imagePlaceholderCount() != epoch.imagePlaceholderCount()
                    || prepared.defaultAltTextCount() != epoch.defaultAltTextCount()
                    || prepared.documentImageDefaultRemovalCount() != epoch.documentImageDefaultRemovalCount()
                    || prepared.emptyInlineTextRemovalCount() != epoch.emptyInlineTextRemovalCount()
                    || !sameContent(manifest.contentFormat(), content, snapshot.content())) {
                throw new IllegalStateException("Node round-trip 校验失败，documentId=" + manifest.documentId());
            }
            verified++;
        }
        System.out.println("Node round-trip 校验通过：" + verified + " 篇；未执行数据库写入。");
    }

    private static RestClient nodeClient(String nodeUrl) {
        if (blank(nodeUrl)) throw new IllegalArgumentException("Node base URL 不能为空");
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(nodeUrl).build();
    }

    private static Map<Long, Manifest> readManifest(Path path) throws IOException {
        Map<Long, Manifest> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("documentId\t")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 6) throw new IllegalArgumentException("manifest 列数无效，行=" + lineNumber);
                long id = Long.parseLong(fields[0]);
                Manifest value = new Manifest(id, Long.parseLong(fields[1]), fields[2],
                        Long.parseLong(fields[3]), fields[4].toLowerCase(), Integer.parseInt(fields[5]));
                if (!"BLOCKNOTE".equals(value.contentFormat()) && !"HTML".equals(value.contentFormat())) {
                    throw new IllegalArgumentException("contentFormat 无效，documentId=" + id);
                }
                if (result.putIfAbsent(id, value) != null) throw new IllegalArgumentException("manifest 文档重复: " + id);
            }
        }
        return result;
    }

    private static Map<Long, TreeMap<Integer, String>> readChunks(Path path) throws IOException {
        Map<Long, TreeMap<Integer, String>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("documentId\t")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) throw new IllegalArgumentException("chunk 列数无效，行=" + lineNumber);
                long id = Long.parseLong(fields[0]);
                int index = Integer.parseInt(fields[1]);
                String hex = fields[2].trim();
                if (hex.isEmpty() || hex.length() > CHUNK_BYTES * 2 || (hex.length() & 1) != 0) {
                    throw new IllegalArgumentException("chunk 长度无效，documentId=" + id + ", index=" + index);
                }
                if (!hex.matches("[0-9A-Fa-f]+")) throw new IllegalArgumentException("chunk HEX 无效");
                TreeMap<Integer, String> documentChunks = result.computeIfAbsent(id, ignored -> new TreeMap<>());
                if (documentChunks.putIfAbsent(index, hex) != null) {
                    throw new IllegalArgumentException("chunk 重复，documentId=" + id + ", index=" + index);
                }
            }
        }
        return result;
    }

    private static Map<Long, Epoch> readEpochs(Path path) throws IOException {
        Map<Long, Epoch> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("documentId\t")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 13) throw new IllegalArgumentException("epochs 列数无效，行=" + lineNumber);
                long id = Long.parseLong(fields[0]);
                Epoch value = new Epoch(id, Long.parseLong(fields[1]), fields[2], fields[3], fields[4], fields[5],
                        Integer.parseInt(fields[6]), Integer.parseInt(fields[7]), Integer.parseInt(fields[8]),
                        Integer.parseInt(fields[9]), Integer.parseInt(fields[10]), Long.parseLong(fields[11]), fields[12]);
                if (result.putIfAbsent(id, value) != null) throw new IllegalArgumentException("epochs 文档重复: " + id);
            }
        }
        return result;
    }

    private static byte[] assemble(Manifest manifest, TreeMap<Integer, String> chunks) throws Exception {
        if (chunks == null || chunks.size() != manifest.chunkCount()) {
            throw new IllegalStateException("正文 chunk 数量不一致，documentId=" + manifest.documentId());
        }
        var output = new java.io.ByteArrayOutputStream();
        int expectedIndex = 0;
        for (Map.Entry<Integer, String> chunk : chunks.entrySet()) {
            if (chunk.getKey() != expectedIndex++) throw new IllegalStateException("正文 chunk 序号断档");
            output.write(HexFormat.of().parseHex(chunk.getValue()));
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length != manifest.contentByteLength()) throw new IllegalStateException("正文字节数不一致");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!sha.equalsIgnoreCase(manifest.contentSha256())) throw new IllegalStateException("正文 SHA-256 不一致");
        return bytes;
    }

    private static String decodeUtf8(byte[] bytes, Long documentId) throws CharacterCodingException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("正文不是有效 UTF-8，documentId=" + documentId, failure);
        }
    }

    static boolean sameContent(String contentFormat, String expected, String actual) throws Exception {
        if (actual == null) return false;
        if ("HTML".equals(contentFormat)) {
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8));
        }
        JsonNode left = JSON.readTree(expected);
        JsonNode right = JSON.readTree(actual);
        if (left == null || right == null) return false;
        JsonNode normalizedLeft = normalizeBlockNoteForComparison(left);
        JsonNode normalizedRight = normalizeBlockNoteForComparison(right);
        return normalizedLeft.equals(normalizedRight);
    }

    private static String firstDifference(JsonNode left, JsonNode right, String path) {
        if (left.equals(right)) return "";
        if (left.isObject() && right.isObject()) {
            for (String name : iterable(left.fieldNames())) {
                if (!right.has(name)) return path + "." + name + "(missing right)";
                String nested = firstDifference(left.get(name), right.get(name), path + "." + name);
                if (!nested.isEmpty()) return nested;
            }
            for (String name : iterable(right.fieldNames())) if (!left.has(name)) return path + "." + name + "(missing left)";
        } else if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return path + "(array " + left.size() + "/" + right.size() + ")";
            for (int index = 0; index < left.size(); index++) {
                String nested = firstDifference(left.get(index), right.get(index), path + "[" + index + "]");
                if (!nested.isEmpty()) return nested;
            }
        }
        return path + "(value)";
    }

    private static Iterable<String> iterable(java.util.Iterator<String> iterator) {
        return () -> iterator;
    }

    private static JsonNode normalizeBlockNoteForComparison(JsonNode source) {
        JsonNode normalized = source.deepCopy();
        normalizeBlockNoteNode(normalized);
        return normalized;
    }

    private static void normalizeBlockNoteNode(JsonNode node) {
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode child : array) normalizeBlockNoteNode(child);
            coalesceIdenticalTextRuns(array);
            absorbPlainNewlineAfterCode(array);
            coalesceIdenticalTextRuns(array);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode object = (ObjectNode) node;
        String type = object.path("type").asText();
        if ("text".equals(type) && object.path("styles").isObject()
                && object.path("styles").isEmpty()) object.remove("styles");
        JsonNode propsNode = object.get("props");
        if ("heading".equals(type) && propsNode instanceof ObjectNode props && !props.has("isToggleable")) {
            props.put("isToggleable", false);
        }
        if (propsNode instanceof ObjectNode props) {
            removeDefaultProp(props, "backgroundColor", "default");
            removeDefaultProp(props, "textColor", "default");
            removeDefaultProp(props, "textAlignment", "left");
            if (props.isEmpty()) object.remove("props");
        }
        object.elements().forEachRemaining(CollaborationStateMigrationTool::normalizeBlockNoteNode);
    }

    private static int removeDefaultProp(ObjectNode props, String name, String value) {
        JsonNode prop = props.get(name);
        if (prop != null && prop.isTextual() && value.equals(prop.asText())) {
            props.remove(name);
            return 1;
        }
        return 0;
    }

    private static void coalesceIdenticalTextRuns(ArrayNode array) {
        for (int index = 1; index < array.size();) {
            JsonNode previous = array.get(index - 1);
            JsonNode current = array.get(index);
            if (!sameTextRunShape(previous, current)) {
                index++;
                continue;
            }
            ObjectNode merged = (ObjectNode) previous;
            merged.put("text", previous.path("text").asText() + current.path("text").asText());
            array.remove(index);
        }
    }

    // BlockNote includes a following line break in the preceding code mark when materializing inline text.
    private static void absorbPlainNewlineAfterCode(ArrayNode array) {
        for (int index = 0; index + 1 < array.size(); index++) {
            JsonNode before = array.get(index);
            JsonNode after = array.get(index + 1);
            if (!before.isObject() || !after.isObject()
                    || !"text".equals(before.path("type").asText())
                    || !"text".equals(after.path("type").asText())
                    || !before.path("styles").path("code").asBoolean(false)
                    || (!after.path("styles").isMissingNode()
                    && (!after.path("styles").isObject() || after.path("styles").size() != 0))
                    || !after.path("text").asText().startsWith("\n")) continue;
            ((ObjectNode) before).put("text", before.path("text").asText() + "\n");
            String rest = after.path("text").asText().substring(1);
            if (rest.isEmpty()) array.remove(index + 1);
            else ((ObjectNode) after).put("text", rest);
        }
    }

    private static boolean sameTextRunShape(JsonNode left, JsonNode right) {
        if (!left.isObject() || !right.isObject()
                || !"text".equals(left.path("type").asText())
                || !"text".equals(right.path("type").asText())) return false;
        if (!left.path("styles").equals(right.path("styles"))) return false;
        var leftFields = new java.util.HashSet<String>();
        left.fieldNames().forEachRemaining(leftFields::add);
        var rightFields = new java.util.HashSet<String>();
        right.fieldNames().forEachRemaining(rightFields::add);
        if (!leftFields.equals(rightFields) || !leftFields.contains("text")) return false;
        for (String field : leftFields) {
            if (!"text".equals(field) && !left.get(field).equals(right.get(field))) return false;
        }
        return true;
    }

    static PreparedContent prepareContent(long documentId, String contentFormat, String sourceContent) throws Exception {
        if (!"BLOCKNOTE".equals(contentFormat)) {
            return new PreparedContent(sourceContent, sha256(sourceContent), 0, 0, 0, 0, 0);
        }
        JsonNode parsed = JSON.readTree(sourceContent);
        if (parsed == null || !parsed.isArray()) {
            throw new IllegalArgumentException("BLOCKNOTE 正文必须是数组，documentId=" + documentId);
        }
        ArrayNode blocks = (ArrayNode) parsed.deepCopy();
        int[] counts = new int[4];
        convertLegacyImages(documentId, blocks, counts);
        int emptyTextRunsRemoved = removeEmptyInlineTextRuns(blocks);
        String migratedContent = JSON.writeValueAsString(blocks);
        return new PreparedContent(migratedContent, sha256(migratedContent), counts[0], counts[1], counts[2],
                counts[3], emptyTextRunsRemoved);
    }

    private static void convertLegacyImages(long documentId, JsonNode node, int[] counts) {
        if (node.isArray()) {
            for (JsonNode child : node) convertLegacyImages(documentId, child, counts);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode block = (ObjectNode) node;
        if ("image".equals(block.path("type").asText())) {
            JsonNode props = block.path("props");
            String blockId = block.path("id").asText("unknown");
            if (isEmptyDataImage(documentId, blockId, props)) {
                replaceWithUnavailableImageParagraph(block);
                counts[1]++;
                convertLegacyImages(documentId, block.path("children"), counts);
                return;
            }
            Long assetId = legacyAttachmentId(documentId, props.path("url").asText(null));
            if (assetId == null) {
                throw new IllegalArgumentException("无法安全转换旧图片，documentId=" + documentId
                        + ", blockId=" + blockId);
            }
            ObjectNode migratedProps = JSON.createObjectNode().put("imageAssetId", assetId);
            JsonNode caption = props.get("caption");
            if (caption != null && !caption.isTextual()) {
                throw new IllegalArgumentException("旧图片 caption 类型无效，documentId=" + documentId
                        + ", blockId=" + blockId);
            }
            migratedProps.set("caption", caption == null ? JSON.getNodeFactory().textNode("") : caption.deepCopy());
            JsonNode name = props.get("name");
            if (name != null && !name.isTextual()) {
                throw new IllegalArgumentException("旧图片 name 类型无效，documentId=" + documentId
                        + ", blockId=" + blockId);
            }
            migratedProps.set("altText", name == null ? JSON.getNodeFactory().textNode("") : name.deepCopy());
            block.put("type", "documentImage");
            block.set("props", migratedProps);
            counts[0]++;
        } else if ("documentImage".equals(block.path("type").asText())
                && block.path("props") instanceof ObjectNode props) {
            if (!props.has("altText")) {
                props.put("altText", "");
                counts[2]++;
            }
            counts[3] += removeDocumentImageDefaultProps(props);
            JsonNode content = block.get("content");
            if (content != null && content.isArray() && content.isEmpty()) {
                block.remove("content");
                counts[3]++;
            }
        }
        JsonNode children = block.get("children");
        if (children != null) convertLegacyImages(documentId, children, counts);
    }

    private static void replaceWithUnavailableImageParagraph(ObjectNode block) {
        block.put("type", "paragraph");
        ObjectNode props = JSON.createObjectNode();
        props.put("textAlignment", "left");
        props.put("textColor", "default");
        props.put("backgroundColor", "default");
        block.set("props", props);
        ObjectNode text = JSON.createObjectNode();
        text.put("type", "text");
        text.put("text", "图片不可用");
        text.set("styles", JSON.createObjectNode());
        ArrayNode content = JSON.createArrayNode().add(text);
        block.set("content", content);
    }

    private static Long legacyAttachmentId(long documentId, String url) {
        String prefix = "/api/project-documents/" + documentId + "/attachments/";
        String suffix = "/download";
        if (url == null || !url.startsWith(prefix) || !url.endsWith(suffix)) return null;
        String id = url.substring(prefix.length(), url.length() - suffix.length());
        if (id.isEmpty() || !id.matches("[0-9]+")) return null;
        try {
            long assetId = Long.parseLong(id);
            return assetId > 0 ? assetId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int removeDocumentImageDefaultProps(ObjectNode props) {
        int removed = 0;
        removed += removeDefaultProp(props, "backgroundColor", "default");
        removed += removeDefaultProp(props, "textColor", "default");
        removed += removeDefaultProp(props, "textAlignment", "left");
        return removed;
    }

    private static boolean isEmptyDataImage(long documentId, String blockId, JsonNode props) {
        for (String key : List.of("url", "src")) {
            String value = props.path(key).asText("");
            if (!value.startsWith("data:image/")) continue;
            if (documentId == 561
                    && ("56aba7dc-3be2-470e-a6e9-c710034296be".equals(blockId)
                    || "f48b3ec2-5f6e-4e77-8ac3-e95d7c2ef6ef".equals(blockId))
                    && "data:image/png;base64...".equals(value)) return true;
            throw new IllegalArgumentException("旧图片 data URI 无可恢复字节或格式不受支持，documentId="
                    + documentId + ", blockId=" + blockId);
        }
        return false;
    }

    private static int removeEmptyInlineTextRuns(JsonNode node) {
        if (node.isArray()) {
            int removed = 0;
            for (JsonNode child : node) removed += removeEmptyInlineTextRuns(child);
            return removed;
        }
        if (!node.isObject()) return 0;
        int removed = 0;
        ObjectNode object = (ObjectNode) node;
        var fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if ("content".equals(field.getKey()) && value.isArray()) {
                ArrayNode content = (ArrayNode) value;
                for (int index = content.size() - 1; index >= 0; index--) {
                    JsonNode candidate = content.get(index);
                    if (isEmptyInlineTextRun(candidate)) {
                        content.remove(index);
                        removed++;
                    } else {
                        removed += removeEmptyInlineTextRuns(candidate);
                    }
                }
            } else {
                removed += removeEmptyInlineTextRuns(value);
            }
        }
        return removed;
    }

    private static boolean isEmptyInlineTextRun(JsonNode node) {
        if (!node.isObject() || !"text".equals(node.path("type").asText())
                || !node.path("text").isTextual() || !node.path("text").asText().isEmpty()) return false;
        JsonNode styles = node.get("styles");
        if (styles != null && !styles.isObject()) return false;
        var names = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names.equals(java.util.Set.of("type", "text", "styles"))
                || names.equals(java.util.Set.of("type", "text"));
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeBatch(Path outputDir, int batch, List<String> rows) throws IOException {
        StringBuilder sql = new StringBuilder("INSERT INTO project_document_collab_state "
                + "(document_id, epoch, commit_seq, state_binary) VALUES\n");
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) sql.append(",\n");
            sql.append(rows.get(index));
        }
        sql.append(";\n");
        if (sql.toString().getBytes(StandardCharsets.UTF_8).length > SQL_BATCH_MAX_BYTES) {
            throw new IllegalStateException("生成的 DBX SQL 超出请求预算");
        }
        Files.writeString(outputDir.resolve("state-batch-%03d.sql".formatted(batch)), sql,
                StandardCharsets.UTF_8);
    }

    private static void writeLargeState(Path outputDir, long documentId, String epoch, String stateBase64)
            throws IOException {
        int part = 1;
        for (int start = 0; start < stateBase64.length(); start += LARGE_STATE_BASE64_PART_CHARS) {
            String encodedPart = stateBase64.substring(start,
                    Math.min(start + LARGE_STATE_BASE64_PART_CHARS, stateBase64.length()));
            String sql = start == 0
                    ? "INSERT INTO project_document_collab_state "
                    + "(document_id, epoch, commit_seq, state_binary) VALUES ("
                    + documentId + ", '" + epoch + "', 1, FROM_BASE64('" + encodedPart + "'));\n"
                    : "UPDATE project_document_collab_state SET state_binary = "
                    + "CONCAT(state_binary, FROM_BASE64('" + encodedPart + "')) WHERE document_id = "
                    + documentId + " AND epoch = '" + epoch + "';\n";
            if (sql.getBytes(StandardCharsets.UTF_8).length > SQL_BATCH_MAX_BYTES) {
                throw new IllegalStateException("分片 SQL 超出请求预算，documentId=" + documentId);
            }
            Files.writeString(outputDir.resolve("state-large-%d-part-%02d.sql".formatted(documentId, part++)),
                    sql, StandardCharsets.UTF_8);
        }
    }

    private static String requiredInternalKey() {
        String key = System.getenv("COLLABORATION_INTERNAL_KEY");
        if (blank(key)) throw new IllegalStateException("COLLABORATION_INTERNAL_KEY 未配置");
        return key;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void usage() {
        throw new IllegalArgumentException("Usage: generate <expected-count> <manifest.tsv> <chunks.tsv> <output-dir> <node-url> "
                + "| verify <expected-count> <manifest.tsv> <chunks.tsv> <epochs.tsv> <node-url>");
    }

    private record Manifest(Long documentId, Long version, String contentFormat,
                            long contentByteLength, String contentSha256, int chunkCount) { }
    private record Epoch(Long documentId, Long version, String epoch, String contentFormat,
                         String sourceContentSha256, String migratedContentSha256, int imageConversionCount,
                         int imagePlaceholderCount, int defaultAltTextCount,
                         int documentImageDefaultRemovalCount, int emptyInlineTextRemovalCount,
                         long stateByteLength, String stateSha256) { }
    record PreparedContent(String content, String contentSha256, int imageConversionCount,
                           int imagePlaceholderCount, int defaultAltTextCount,
                           int documentImageDefaultRemovalCount, int emptyInlineTextRemovalCount) { }
    private record EncodeRequest(String contentFormat, String content) { }
    private record EncodeResponse(String stateBase64, String stateSha256, String content, Long contentBytes,
                                  List<Long> imageAssetIds) { }
    private record SnapshotResponse(String epoch, Long version, String contentFormat, String content) { }
    private static final class CollaborationNodeClientHeader {
        private static final String VALUE = "X-Collaboration-Internal-Key";
    }
}
