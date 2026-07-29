package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.SearchableProjectContent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** OpenAI-compatible Embedding + Qdrant 的统一项目内容索引。 */
@Service
public class ProjectContentSemanticSearchService {
    private static final Logger log = LoggerFactory.getLogger(ProjectContentSemanticSearchService.class);
    private final SemanticSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ProjectContentSemanticSearchService(SemanticSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!properties.isEnabled()) return;
        if (blank(properties.getEmbeddingBaseUrl()) || blank(properties.getEmbeddingApiKey())
                || blank(properties.getQdrantApiKey())) {
            throw new IllegalStateException("启用语义搜索时必须配置 Embedding 与 Qdrant 密钥");
        }
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ensureCollectionOnStartup() {
        if (!properties.isEnabled()) return;
        try {
            ensureCollection();
        } catch (RuntimeException e) {
            log.error("项目内容语义搜索初始化失败", e);
        }
    }

    public List<SemanticContentHit> search(List<Long> projectIds, String query) {
        if (!properties.isEnabled()) throw new IllegalStateException("语义搜索未启用");
        if (projectIds == null || projectIds.isEmpty()) return List.of();
        Map<String, Object> body = Map.of(
                "query", embedding(query),
                "filter", Map.of("must", List.of(
                        Map.of("key", "projectId", "match", Map.of("any", projectIds)))),
                "limit", properties.getMaxResults(),
                "score_threshold", properties.getMinScore(),
                "with_payload", List.of("contentType", "resourceId"));
        JsonNode result = qdrant("POST", "/collections/" + properties.getCollection() + "/points/query", body);
        Set<SemanticContentHit> hits = new LinkedHashSet<>();
        for (JsonNode point : result.path("result").path("points")) {
            JsonNode payload = point.path("payload");
            String contentType = payload.path("contentType").asText("");
            String resourceId = payload.path("resourceId").asText("");
            if (!contentType.isBlank() && !resourceId.isBlank()) {
                hits.add(new SemanticContentHit(contentType, resourceId));
            }
        }
        return List.copyOf(hits);
    }

    public void ensureCollection() {
        try {
            qdrant("GET", "/collections/" + properties.getCollection(), null);
        } catch (RuntimeException notFound) {
            qdrant("PUT", "/collections/" + properties.getCollection(), Map.of(
                    "vectors", Map.of("size", properties.getDimension(), "distance", "Cosine")));
        }
        createPayloadIndex("projectId", "integer");
        createPayloadIndex("contentType", "keyword");
        createPayloadIndex("numericId", "integer");
    }

    public void upsert(SearchableProjectContent content) {
        String text = searchableText(content);
        if (text.isBlank()) {
            delete(content.getContentType(), content.getNumericId());
            return;
        }
        Map<String, Object> payload = Map.of(
                "contentType", content.getContentType(),
                "resourceId", content.getResourceId(),
                "numericId", content.getNumericId(),
                "projectId", content.getProjectId());
        Map<String, Object> point = Map.of(
                "id", pointId(content.getContentType(), content.getNumericId()),
                "vector", embedding(text),
                "payload", payload);
        qdrant("PUT", "/collections/" + properties.getCollection() + "/points?wait=true",
                Map.of("points", List.of(point)));
    }

    public void delete(String contentType, Long numericId) {
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "contentType", "match", Map.of("value", contentType)),
                Map.of("key", "numericId", "match", Map.of("value", numericId))));
        qdrant("POST", "/collections/" + properties.getCollection() + "/points/delete?wait=true",
                Map.of("filter", filter));
    }

    public String contentHash(SearchableProjectContent content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(searchableText(content).replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public String visibleText(SearchableProjectContent content) {
        return ProjectContentTextExtractor.extract(ProjectContentType.valueOf(content.getContentType()),
                content.getSourceContent());
    }

    private String searchableText(SearchableProjectContent content) {
        String title = content.getTitle() == null ? "" : content.getTitle().trim();
        return (title + "\n" + visibleText(content)).trim();
    }

    private void createPayloadIndex(String field, String schema) {
        qdrant("PUT", "/collections/" + properties.getCollection() + "/index?wait=true",
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
                throw new IllegalStateException("远程向量服务返回 HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("远程向量服务请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("远程向量服务请求被中断", e);
        }
    }

    private static String pointId(String contentType, Long numericId) {
        return UUID.nameUUIDFromBytes((contentType + ":" + numericId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeUrl(String value) { return value.replaceAll("/+$", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record SemanticContentHit(String contentType, String resourceId) {
    }
}
