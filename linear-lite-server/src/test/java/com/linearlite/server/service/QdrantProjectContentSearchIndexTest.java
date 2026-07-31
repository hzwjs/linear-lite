package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.SearchableProjectContent;
import com.linearlite.server.service.ProjectContentSearchIndex.SearchScope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantProjectContentSearchIndexTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new ArrayList<>();
    private HttpServer server;
    private QdrantProjectContentSearchIndex index;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
        SemanticSearchProperties properties = new SemanticSearchProperties();
        properties.setEnabled(true);
        properties.setDimension(2);
        properties.setQdrantUrl(baseUrl());
        properties.setQdrantApiKey("qdrant-key");
        properties.setEmbeddingBaseUrl(baseUrl());
        properties.setEmbeddingApiKey("embedding-key");
        properties.setCollection("content");
        index = new QdrantProjectContentSearchIndex(properties, objectMapper, new ProjectContentLiteralCodec());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsWhitespacePhraseIndexesForTitleAndBody() {
        index.ensureCollection();

        List<JsonNode> indexBodies = requests.stream()
                .filter(request -> request.path().contains("/index"))
                .map(request -> read(request.body())).toList();
        assertEquals(6, indexBodies.size());
        for (String field : List.of("titleLiteralTokens", "bodyLiteralTokens")) {
            JsonNode body = indexBodies.stream().filter(item -> field.equals(item.path("field_name").asText()))
                    .findFirst().orElseThrow();
            assertEquals("whitespace", body.path("field_schema").path("tokenizer").asText());
            assertTrue(body.path("field_schema").path("phrase_matching").asBoolean());
            assertFalse(body.path("field_schema").path("lowercase").asBoolean());
        }
    }

    @Test
    void allThreeQueriesUseTheSameProjectFilterAndLimit() {
        SearchScope scope = new SearchScope(List.of(7L, 8L),
                List.of(ProjectContentType.TASK, ProjectContentType.DOCUMENT));
        index.searchTitle(scope, "派单");
        index.searchBody(scope, "派单");
        index.searchSemantic(scope, "派单");

        List<JsonNode> qdrantQueries = requests.stream()
                .filter(request -> request.path().startsWith("/collections/"))
                .map(request -> read(request.body())).toList();
        assertEquals(3, qdrantQueries.size());
        qdrantQueries.forEach(body -> {
            assertEquals(50, body.path("limit").asInt());
            assertTrue(body.path("filter").toString().contains("\"projectId\""));
            assertTrue(body.path("filter").toString().contains("[7,8]"));
            assertTrue(body.path("filter").toString().contains("\"contentType\""));
            assertTrue(body.path("filter").toString().contains("[\"TASK\",\"DOCUMENT\"]"));
            assertFalse(body.path("with_payload").toString().contains("LiteralTokens"));
        });
        assertEquals("u6d3e u5355", qdrantQueries.get(0).path("filter").path("must").path(2)
                .path("match").path("phrase").asText());
        assertEquals("u6d3e u5355", qdrantQueries.get(1).path("filter").path("must").path(2)
                .path("match").path("phrase").asText());
    }

    @Test
    void documentScopeIsPushedToAllThreeQdrantChannels() {
        SearchScope scope = new SearchScope(List.of(7L), List.of(ProjectContentType.DOCUMENT));

        index.searchTitle(scope, "派单");
        index.searchBody(scope, "派单");
        index.searchSemantic(scope, "派单");

        List<JsonNode> qdrantQueries = requests.stream()
                .filter(request -> request.path().startsWith("/collections/"))
                .map(request -> read(request.body())).toList();
        assertEquals(3, qdrantQueries.size());
        qdrantQueries.forEach(body -> {
            JsonNode must = body.path("filter").path("must");
            assertEquals(7L, must.path(0).path("match").path("any").path(0).asLong());
            assertEquals("DOCUMENT", must.path(1).path("match").path("any").path(0).asText());
            assertEquals(1, must.path(1).path("match").path("any").size());
        });
    }

    @Test
    void upsertStoresLiteralTokensButKeepsSourceContentOutOfResultFields() {
        SearchableProjectContent content = new SearchableProjectContent();
        content.setContentType("TASK");
        content.setNumericId(3L);
        content.setResourceId("PHX-3");
        content.setProjectId(7L);
        content.setTitle("派单");
        content.setSourceContent("执行任务");
        content.setSourceUpdatedAt(LocalDateTime.of(2026, 7, 30, 12, 0));

        index.upsert(content);

        JsonNode payload = requests.stream().filter(request -> request.path().contains("/points?wait=true"))
                .map(request -> read(request.body())).findFirst().orElseThrow()
                .path("points").path(0).path("payload");
        assertEquals("u6d3e u5355", payload.path("titleLiteralTokens").asText());
        assertEquals("u6267 u884c u4efb u52a1", payload.path("bodyLiteralTokens").asText());
        assertFalse(payload.has("sourceContent"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
        String response = exchange.getRequestURI().getPath().endsWith("/embeddings")
                ? "{\"data\":[{\"embedding\":[0.1,0.2]}]}"
                : exchange.getRequestURI().getPath().endsWith("/points/query")
                    || exchange.getRequestURI().getPath().endsWith("/points/scroll")
                    ? "{\"result\":{\"points\":[]}}" : "{\"result\":{}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
