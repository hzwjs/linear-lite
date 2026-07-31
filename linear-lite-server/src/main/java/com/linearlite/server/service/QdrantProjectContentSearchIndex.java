package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.SearchableProjectContent;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Qdrant 精准字面索引与语义向量索引的唯一 Implementation。 */
@Component
public class QdrantProjectContentSearchIndex implements ProjectContentSearchIndex {
    private static final Logger log = LoggerFactory.getLogger(QdrantProjectContentSearchIndex.class);
    private static final int EXCERPT_CODE_POINTS = 180;
    private static final List<String> RESULT_PAYLOAD = List.of(
            "contentType", "numericId", "resourceId", "projectId", "title", "excerpt", "sourceUpdatedAtEpoch");

    private final SemanticSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final ProjectContentLiteralCodec literalCodec;
    private final HttpClient httpClient;

    @Autowired
    public QdrantProjectContentSearchIndex(SemanticSearchProperties properties, ObjectMapper objectMapper,
                                           ProjectContentLiteralCodec literalCodec) {
        this(properties, objectMapper, literalCodec,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    QdrantProjectContentSearchIndex(SemanticSearchProperties properties, ObjectMapper objectMapper,
                                    ProjectContentLiteralCodec literalCodec, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.literalCodec = literalCodec;
        this.httpClient = httpClient;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!properties.isEnabled()) return;
        if (blank(properties.getEmbeddingBaseUrl()) || blank(properties.getEmbeddingApiKey())
                || blank(properties.getQdrantApiKey())) {
            throw new IllegalStateException("启用项目内容搜索时必须配置 Embedding 与 Qdrant 密钥");
        }
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ensureCollectionOnStartup() {
        if (!properties.isEnabled()) return;
        try {
            ensureCollection();
        } catch (RuntimeException e) {
            log.error("项目内容搜索索引初始化失败", e);
        }
    }

    @Override
    public List<SearchHit> searchTitle(SearchScope scope, String query) {
        return literalSearch("titleLiteralTokens", scope, query);
    }

    @Override
    public List<SearchHit> searchBody(SearchScope scope, String query) {
        return literalSearch("bodyLiteralTokens", scope, query);
    }

    @Override
    public List<SearchHit> searchSemantic(SearchScope scope, String query) {
        requireSearchable(scope);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", embedding(query));
        body.put("filter", scopeFilter(scope));
        body.put("limit", CHANNEL_LIMIT);
        body.put("score_threshold", properties.getMinScore());
        body.put("with_payload", RESULT_PAYLOAD);
        JsonNode result = qdrant("POST", collectionPath("/points/query"), body);
        return readHits(result.path("result").path("points"), true);
    }

    private List<SearchHit> literalSearch(String field, SearchScope scope, String query) {
        requireSearchable(scope);
        List<Map<String, Object>> must = new ArrayList<>(scopeConditions(scope));
        must.add(Map.of("key", field, "match", Map.of("phrase", literalCodec.encode(query))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", Map.of("must", must));
        body.put("limit", CHANNEL_LIMIT);
        body.put("with_payload", RESULT_PAYLOAD);
        body.put("with_vector", false);
        body.put("order_by", Map.of("key", "sourceUpdatedAtEpoch", "direction", "desc"));
        JsonNode result = qdrant("POST", collectionPath("/points/scroll"), body);
        return readHits(result.path("result").path("points"), false);
    }

    @Override
    public void ensureCollection() {
        try {
            qdrant("GET", collectionPath(""), null);
        } catch (RuntimeException notFound) {
            qdrant("PUT", collectionPath(""), Map.of(
                    "vectors", Map.of("size", properties.getDimension(), "distance", "Cosine")));
        }
        createPayloadIndex("projectId", "integer");
        createPayloadIndex("contentType", "keyword");
        createPayloadIndex("numericId", "integer");
        createPayloadIndex("sourceUpdatedAtEpoch", "integer");
        Map<String, Object> textSchema = Map.of(
                "type", "text", "tokenizer", "whitespace", "min_token_len", 1,
                "lowercase", false, "phrase_matching", true);
        createPayloadIndex("titleLiteralTokens", textSchema);
        createPayloadIndex("bodyLiteralTokens", textSchema);
    }

    @Override
    public void upsert(SearchableProjectContent content) {
        String visibleText = visibleText(content);
        String title = content.getTitle() == null ? "" : content.getTitle().trim();
        String searchableText = (title + "\n" + visibleText).trim();
        if (searchableText.isBlank()) {
            delete(content.getContentType(), content.getNumericId());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentType", content.getContentType());
        payload.put("numericId", content.getNumericId());
        payload.put("resourceId", content.getResourceId());
        payload.put("projectId", content.getProjectId());
        payload.put("title", title);
        payload.put("excerpt", excerpt(visibleText));
        payload.put("sourceUpdatedAtEpoch", epoch(content.getSourceUpdatedAt()));
        // 字面字段只参与 Qdrant 倒排索引，搜索响应明确排除，正文不会回传给 Java。
        payload.put("titleLiteralTokens", literalCodec.encode(title));
        payload.put("bodyLiteralTokens", literalCodec.encode(visibleText));
        payload.put("contentHash", contentHash(content));

        Map<String, Object> point = Map.of(
                "id", pointId(content.getContentType(), content.getNumericId()),
                "vector", embedding(searchableText),
                "payload", payload);
        qdrant("PUT", collectionPath("/points?wait=true"), Map.of("points", List.of(point)));
    }

    @Override
    public void delete(String contentType, Long numericId) {
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "contentType", "match", Map.of("value", contentType)),
                Map.of("key", "numericId", "match", Map.of("value", numericId))));
        qdrant("POST", collectionPath("/points/delete?wait=true"), Map.of("filter", filter));
    }

    @Override
    public String contentHash(SearchableProjectContent content) {
        try {
            String value = ((content.getTitle() == null ? "" : content.getTitle()) + "\n" + visibleText(content))
                    .replaceAll("\\s+", " ");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String visibleText(SearchableProjectContent content) {
        return ProjectContentTextExtractor.extract(ProjectContentType.valueOf(content.getContentType()),
                content.getSourceContent());
    }

    private List<SearchHit> readHits(JsonNode points, boolean includeScore) {
        if (!points.isArray()) return List.of();
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode point : points) {
            JsonNode payload = point.path("payload");
            if (payload.path("contentType").asText().isBlank() || payload.path("resourceId").asText().isBlank()) {
                continue;
            }
            hits.add(new SearchHit(payload.path("contentType").asText(), payload.path("numericId").asLong(),
                    payload.path("resourceId").asText(), payload.path("projectId").asLong(),
                    payload.path("title").asText(""), payload.path("excerpt").asText(""),
                    payload.path("sourceUpdatedAtEpoch").asLong(), includeScore ? point.path("score").asDouble() : 0));
        }
        return List.copyOf(hits);
    }

    private void createPayloadIndex(String field, Object schema) {
        qdrant("PUT", collectionPath("/index?wait=true"),
                Map.of("field_name", field, "field_schema", schema));
    }

    private List<Double> embedding(String input) {
        JsonNode response = request("POST", normalizeUrl(properties.getEmbeddingBaseUrl()) + "/embeddings",
                Map.of("model", properties.getEmbeddingModel(), "input", input), properties.getEmbeddingApiKey());
        JsonNode values = response.path("data").path(0).path("embedding");
        if (!values.isArray() || values.size() != properties.getDimension()) {
            throw new IllegalStateException("Embedding 维度不匹配，期望=" + properties.getDimension() + "，实际=" + values.size());
        }
        List<Double> vector = new ArrayList<>(values.size());
        values.forEach(value -> vector.add(value.asDouble()));
        return vector;
    }

    private Map<String, Object> scopeFilter(SearchScope scope) {
        return Map.of("must", scopeConditions(scope));
    }

    private List<Map<String, Object>> scopeConditions(SearchScope scope) {
        // 项目与内容类型共同进入 Qdrant filter，三个通道不会先召回越界候选再由 Java 丢弃。
        return List.of(
                projectCondition(scope.projectIds()),
                Map.of("key", "contentType", "match", Map.of("any",
                        scope.contentTypes().stream().map(ProjectContentType::name).toList())));
    }

    private Map<String, Object> projectCondition(List<Long> projectIds) {
        return Map.of("key", "projectId", "match", Map.of("any", projectIds));
    }

    private void requireSearchable(SearchScope scope) {
        if (!properties.isEnabled()) throw new IllegalStateException("项目内容搜索未启用");
        if (scope == null) throw new IllegalArgumentException("搜索范围不能为空");
    }

    private JsonNode qdrant(String method, String path, Object body) {
        return request(method, normalizeUrl(properties.getQdrantUrl()) + path, body, properties.getQdrantApiKey());
    }

    private JsonNode request(String method, String url, Object body, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            if (!blank(apiKey)) builder.header("Authorization", "Bearer " + apiKey).header("api-key", apiKey);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("远程搜索服务返回 HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("远程搜索服务请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("远程搜索服务请求被中断", e);
        }
    }

    private String collectionPath(String suffix) {
        return "/collections/" + properties.getCollection() + suffix;
    }

    private static String excerpt(String value) {
        int count = value.codePointCount(0, value.length());
        if (count <= EXCERPT_CODE_POINTS) return value;
        return value.substring(0, value.offsetByCodePoints(0, EXCERPT_CODE_POINTS));
    }

    private static long epoch(LocalDateTime value) {
        return value == null ? 0 : value.toEpochSecond(ZoneOffset.UTC);
    }

    private static String pointId(String contentType, Long numericId) {
        return UUID.nameUUIDFromBytes((contentType + ":" + numericId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeUrl(String value) { return value.replaceAll("/+$", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
