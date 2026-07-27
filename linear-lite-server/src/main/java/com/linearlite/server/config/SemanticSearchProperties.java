package com.linearlite.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.semantic-search")
public class SemanticSearchProperties {
    private boolean enabled;
    private String qdrantUrl = "http://127.0.0.1:6333";
    private String qdrantApiKey;
    private String collection = "linear_lite_tasks";
    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private String embeddingModel = "qwen3-embedding";
    private int dimension = 1024;
    private int maxResults = 100;
    private double minScore = 0.35;
    private int indexDebounceSeconds = 30;
    private int indexWorkerBatchSize = 20;
    private boolean initialBackfillEnabled;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getQdrantUrl() { return qdrantUrl; }
    public void setQdrantUrl(String qdrantUrl) { this.qdrantUrl = qdrantUrl; }
    public String getQdrantApiKey() { return qdrantApiKey; }
    public void setQdrantApiKey(String qdrantApiKey) { this.qdrantApiKey = qdrantApiKey; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
    public int getIndexDebounceSeconds() { return indexDebounceSeconds; }
    public void setIndexDebounceSeconds(int indexDebounceSeconds) { this.indexDebounceSeconds = indexDebounceSeconds; }
    public int getIndexWorkerBatchSize() { return indexWorkerBatchSize; }
    public void setIndexWorkerBatchSize(int indexWorkerBatchSize) { this.indexWorkerBatchSize = indexWorkerBatchSize; }
    public boolean isInitialBackfillEnabled() { return initialBackfillEnabled; }
    public void setInitialBackfillEnabled(boolean initialBackfillEnabled) { this.initialBackfillEnabled = initialBackfillEnabled; }
}
