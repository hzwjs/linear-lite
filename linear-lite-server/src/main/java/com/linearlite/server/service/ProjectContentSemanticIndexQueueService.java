package com.linearlite.server.service;

import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.ProjectContentSemanticIndexJob;
import com.linearlite.server.entity.SearchableProjectContent;
import com.linearlite.server.mapper.ProjectContentSearchMapper;
import com.linearlite.server.mapper.ProjectContentSemanticIndexJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/** 将任务和文档的高频保存合并到同一条延迟索引队列。 */
@Service
public class ProjectContentSemanticIndexQueueService {
    private static final Logger log = LoggerFactory.getLogger(ProjectContentSemanticIndexQueueService.class);
    private static final String UPSERT = "UPSERT";
    private static final String DELETE = "DELETE";

    private final SemanticSearchProperties properties;
    private final ProjectContentSemanticIndexJobMapper jobMapper;
    private final ProjectContentSearchMapper contentMapper;
    private final ProjectContentSemanticSearchService semanticSearchService;

    public ProjectContentSemanticIndexQueueService(SemanticSearchProperties properties,
                                                   ProjectContentSemanticIndexJobMapper jobMapper,
                                                   ProjectContentSearchMapper contentMapper,
                                                   ProjectContentSemanticSearchService semanticSearchService) {
        this.properties = properties;
        this.jobMapper = jobMapper;
        this.contentMapper = contentMapper;
        this.semanticSearchService = semanticSearchService;
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void queueExistingContent() {
        if (!properties.isEnabled() || !properties.isInitialBackfillEnabled()) return;
        contentMapper.selectAllTaskIds().forEach(id -> enqueueUpsert(ProjectContentType.TASK, id, false));
        contentMapper.selectAllDocumentIds().forEach(id -> enqueueUpsert(ProjectContentType.DOCUMENT, id, false));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContentChanged(ProjectContentSemanticIndexRequestedEvent event) {
        enqueueUpsert(event.contentType(), event.resourceId(), true);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContentDeleted(ProjectContentSemanticDeleteRequestedEvent event) {
        enqueueDelete(event.contentType(), event.resourceIds());
    }

    @Scheduled(fixedDelayString = "${app.semantic-search.index-worker-delay-millis:1000}")
    public void processDueJobs() {
        if (!properties.isEnabled()) return;
        for (ProjectContentSemanticIndexJob job
                : jobMapper.selectDue(LocalDateTime.now(), properties.getIndexWorkerBatchSize())) {
            process(job);
        }
    }

    void enqueueUpsert(ProjectContentType type, Long resourceId, boolean delayed) {
        if (!properties.isEnabled() || type == null || resourceId == null) return;
        SearchableProjectContent content = selectContent(type, resourceId);
        if (content == null) {
            enqueueDelete(type, List.of(resourceId));
            return;
        }
        LocalDateTime runAfter = LocalDateTime.now()
                .plusSeconds(delayed ? properties.getIndexDebounceSeconds() : 0);
        jobMapper.upsert(type.name(), resourceId, UPSERT, semanticSearchService.contentHash(content), runAfter);
    }

    public void enqueueDelete(ProjectContentType type, List<Long> resourceIds) {
        if (!properties.isEnabled() || type == null || resourceIds == null) return;
        LocalDateTime now = LocalDateTime.now();
        resourceIds.stream().filter(id -> id != null).distinct()
                .forEach(id -> jobMapper.upsert(type.name(), id, DELETE, null, now));
    }

    private void process(ProjectContentSemanticIndexJob job) {
        ProjectContentType type = ProjectContentType.valueOf(job.getContentType());
        try {
            if (DELETE.equals(job.getOperation())) {
                semanticSearchService.delete(type.name(), job.getResourceId());
            } else {
                SearchableProjectContent content = selectContent(type, job.getResourceId());
                if (content == null) {
                    semanticSearchService.delete(type.name(), job.getResourceId());
                } else {
                    String currentHash = semanticSearchService.contentHash(content);
                    if (!currentHash.equals(job.getContentHash())) {
                        enqueueUpsert(type, job.getResourceId(), true);
                        return;
                    }
                    semanticSearchService.upsert(content);
                }
            }
            jobMapper.deleteIfVersion(type.name(), job.getResourceId(), job.getVersion());
        } catch (RuntimeException e) {
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            long delaySeconds = Math.min(300, 1L << Math.min(attempts + 1, 8));
            jobMapper.rescheduleIfVersion(type.name(), job.getResourceId(), job.getVersion(),
                    LocalDateTime.now().plusSeconds(delaySeconds));
            log.error("项目内容语义索引处理失败 type={} resourceId={} operation={}",
                    type, job.getResourceId(), job.getOperation(), e);
        }
    }

    private SearchableProjectContent selectContent(ProjectContentType type, Long resourceId) {
        return type == ProjectContentType.TASK
                ? contentMapper.selectTask(resourceId)
                : contentMapper.selectDocument(resourceId);
    }
}
