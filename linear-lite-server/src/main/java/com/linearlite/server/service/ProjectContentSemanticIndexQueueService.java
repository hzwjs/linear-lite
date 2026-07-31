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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 将任务和文档的高频保存合并到同一条延迟索引队列。 */
@Service
public class ProjectContentSemanticIndexQueueService {
    private static final Logger log = LoggerFactory.getLogger(ProjectContentSemanticIndexQueueService.class);
    private static final String UPSERT = "UPSERT";
    private static final String DELETE = "DELETE";
    // 覆盖 Embedding 与 Qdrant 两次远程调用的超时总和，避免同代次被重复领取。
    private static final long LEASE_SECONDS = 180;

    private final SemanticSearchProperties properties;
    private final ProjectContentSemanticIndexJobMapper jobMapper;
    private final ProjectContentSearchMapper contentMapper;
    private final ProjectContentSearchIndex searchIndex;

    public ProjectContentSemanticIndexQueueService(SemanticSearchProperties properties,
                                                   ProjectContentSemanticIndexJobMapper jobMapper,
                                                   ProjectContentSearchMapper contentMapper,
                                                   ProjectContentSearchIndex searchIndex) {
        this.properties = properties;
        this.jobMapper = jobMapper;
        this.contentMapper = contentMapper;
        this.searchIndex = searchIndex;
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void queueExistingContent() {
        if (!properties.isEnabled() || !properties.isInitialBackfillEnabled()) return;
        contentMapper.selectAllTaskIds().forEach(id -> enqueueUpsert(ProjectContentType.TASK, id, false));
        contentMapper.selectAllDocumentIds().forEach(id -> enqueueUpsert(ProjectContentType.DOCUMENT, id, false));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onContentChanged(ProjectContentSemanticIndexRequestedEvent event) {
        // 同步监听并加入业务事务，业务回滚时索引任务也必须一并回滚。
        enqueueUpsert(event.contentType(), event.resourceId(), true);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onContentDeleted(ProjectContentSemanticDeleteRequestedEvent event) {
        // 删除任务与源数据删除共用事务，消除 AFTER_COMMIT 的事件丢失窗口。
        enqueueDelete(event.contentType(), event.resourceIds());
    }

    @Scheduled(fixedDelayString = "${app.semantic-search.index-worker-delay-millis:1000}")
    public void processDueJobs() {
        if (!properties.isEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        for (ProjectContentSemanticIndexJob job
                : jobMapper.selectDue(now, properties.getIndexWorkerBatchSize())) {
            // 先按代次抢占租约；只有一个实例能够处理本次扫描到的任务。
            int claimed = jobMapper.claimLease(job.getContentType(), job.getResourceId(), job.getGeneration(),
                    now, now.plusSeconds(LEASE_SECONDS));
            if (claimed == 1) {
                process(job);
            }
        }
    }

    void enqueueUpsert(ProjectContentType type, Long resourceId, boolean delayed) {
        if (!properties.isEnabled() || type == null || resourceId == null) return;
        LocalDateTime runAfter = LocalDateTime.now()
                .plusSeconds(delayed ? properties.getIndexDebounceSeconds() : 0);
        jobMapper.upsert(type.name(), resourceId, UPSERT, runAfter);
    }

    public void enqueueDelete(ProjectContentType type, List<Long> resourceIds) {
        if (!properties.isEnabled() || type == null || resourceIds == null) return;
        LocalDateTime now = LocalDateTime.now();
        resourceIds.stream().filter(id -> id != null).distinct()
                .forEach(id -> jobMapper.upsert(type.name(), id, DELETE, now));
    }

    private void process(ProjectContentSemanticIndexJob job) {
        ProjectContentType type = ProjectContentType.valueOf(job.getContentType());
        try {
            if (DELETE.equals(job.getOperation())) {
                searchIndex.delete(type.name(), job.getResourceId());
            } else {
                SearchableProjectContent content = selectContent(type, job.getResourceId());
                if (content == null) {
                    // 源记录不存在时只有 DELETE 一条固定路径，禁止回查或兼容其他数据源。
                    searchIndex.delete(type.name(), job.getResourceId());
                } else {
                    searchIndex.upsert(content);
                }
            }
            // 处理期间若业务写入了新代次，旧 Worker 不得删除新任务。
            jobMapper.deleteIfGeneration(type.name(), job.getResourceId(), job.getGeneration());
        } catch (RuntimeException e) {
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            long delaySeconds = Math.min(300, 1L << Math.min(attempts + 1, 8));
            // 仅同代次失败任务退避；新代次保留自己的立即执行时间。
            jobMapper.rescheduleIfGeneration(type.name(), job.getResourceId(), job.getGeneration(),
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
