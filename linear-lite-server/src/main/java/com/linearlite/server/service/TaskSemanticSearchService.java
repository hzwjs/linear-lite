package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskMapper;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** OpenAI-compatible Embedding + Qdrant REST implementation for task title/description retrieval. */
@Service
public class TaskSemanticSearchService {
    private static final Logger log = LoggerFactory.getLogger(TaskSemanticSearchService.class);
    private final SemanticSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskMapper taskMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TaskSemanticSearchService(SemanticSearchProperties properties, ObjectMapper objectMapper, TaskMapper taskMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskMapper = taskMapper;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!properties.isEnabled()) return;
        if (blank(properties.getEmbeddingBaseUrl()) || blank(properties.getEmbeddingApiKey()) || blank(properties.getQdrantApiKey())) {
            throw new IllegalStateException("启用语义搜索时必须配置 Embedding 与 Qdrant 密钥");
        }
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ensureCollectionAndIndexExistingTasks() {
        if (!properties.isEnabled()) return;
        try {
            ensureCollection();
        } catch (RuntimeException e) {
            log.error("语义搜索初始化失败", e);
        }
    }

    public List<String> search(List<Long> projectIds, String query) {
        if (!properties.isEnabled()) throw new IllegalStateException("语义搜索未启用");
        if (projectIds == null || projectIds.isEmpty()) return List.of();
        List<Double> vector = embedding(query);
        Map<String, Object> body = Map.of(
                "query", vector,
                "filter", Map.of("must", List.of(Map.of("key", "projectId", "match", Map.of("any", projectIds)))),
                "limit", properties.getMaxResults(),
                "score_threshold", properties.getMinScore(),
                "with_payload", List.of("taskKey"));
        JsonNode result = qdrant("POST", "/collections/" + properties.getCollection() + "/points/query", body);
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode point : result.path("result").path("points")) {
            String key = point.path("payload").path("taskKey").asText("");
            if (!key.isBlank()) keys.add(key);
        }
        return List.copyOf(keys);
    }

    public void ensureCollection() {
        try {
            qdrant("GET", "/collections/" + properties.getCollection(), null);
        } catch (RuntimeException notFound) {
            qdrant("PUT", "/collections/" + properties.getCollection(), Map.of(
                    "vectors", Map.of("size", properties.getDimension(), "distance", "Cosine")));
        }
        qdrant("PUT", "/collections/" + properties.getCollection() + "/index?wait=true", Map.of(
                "field_name", "projectId", "field_schema", "integer"));
    }

    public void upsert(Task task) {
        String text = searchableText(task);
        if (text.isBlank()) return;
        List<Double> vector = embedding(text);
        Map<String, Object> point = Map.of(
                "id", task.getId(),
                "vector", vector,
                "payload", Map.of("projectId", task.getProjectId(), "taskKey", task.getTaskKey()));
        qdrant("PUT", "/collections/" + properties.getCollection() + "/points?wait=true", Map.of("points", List.of(point)));
    }

    public void deletePoints(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return;
        qdrant("POST", "/collections/" + properties.getCollection() + "/points/delete?wait=true",
                Map.of("points", taskIds));
    }

    public String contentHash(Task task) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(searchableText(task).replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
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

    private static String searchableText(Task task) {
        String title = task.getTitle() == null ? "" : task.getTitle().trim();
        String description = task.getDescription() == null ? "" : task.getDescription()
                .replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").trim();
        return (title + "\n" + description).trim();
    }

    private static String normalizeUrl(String value) { return value.replaceAll("/+$", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
