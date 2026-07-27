package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskSemanticIndexJob;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.TaskSemanticIndexJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 将高频自动保存合并为按任务去重的延迟语义索引任务。 */
@Service
public class TaskSemanticIndexQueueService {
    private static final Logger log = LoggerFactory.getLogger(TaskSemanticIndexQueueService.class);
    private static final String UPSERT = "UPSERT";
    private static final String DELETE = "DELETE";

    private final SemanticSearchProperties properties;
    private final TaskSemanticIndexJobMapper jobMapper;
    private final TaskMapper taskMapper;
    private final TaskSemanticSearchService semanticSearchService;

    public TaskSemanticIndexQueueService(SemanticSearchProperties properties, TaskSemanticIndexJobMapper jobMapper,
                                         TaskMapper taskMapper, TaskSemanticSearchService semanticSearchService) {
        this.properties = properties;
        this.jobMapper = jobMapper;
        this.taskMapper = taskMapper;
        this.semanticSearchService = semanticSearchService;
    }

    public void enqueueUpsert(Long taskId) {
        enqueueUpsert(taskId, true);
    }

    void enqueueUpsertImmediately(Long taskId) {
        enqueueUpsert(taskId, false);
    }

    public void enqueueDelete(List<Long> taskIds) {
        if (!properties.isEnabled() || taskIds == null) return;
        LocalDateTime now = LocalDateTime.now();
        taskIds.stream().filter(id -> id != null).distinct()
                .forEach(id -> jobMapper.upsert(id, DELETE, null, now));
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void queueExistingTasks() {
        if (!properties.isEnabled() || !properties.isInitialBackfillEnabled()) return;
        for (Task task : taskMapper.selectList(null)) enqueueUpsertImmediately(task.getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskChanged(TaskSemanticIndexRequestedEvent event) {
        enqueueUpsert(event.taskId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTasksDeleted(TaskSemanticDeleteRequestedEvent event) {
        enqueueDelete(event.taskIds());
    }

    @Scheduled(fixedDelayString = "${app.semantic-search.index-worker-delay-millis:1000}")
    public void processDueJobs() {
        if (!properties.isEnabled()) return;
        for (TaskSemanticIndexJob job : jobMapper.selectDue(LocalDateTime.now(), properties.getIndexWorkerBatchSize())) {
            process(job);
        }
    }

    private void enqueueUpsert(Long taskId, boolean delayed) {
        if (!properties.isEnabled() || taskId == null) return;
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            enqueueDelete(List.of(taskId));
            return;
        }
        String hash = semanticSearchService.contentHash(task);
        if (hash.equals(task.getSemanticIndexHash())) return;
        LocalDateTime runAfter = LocalDateTime.now().plusSeconds(delayed ? properties.getIndexDebounceSeconds() : 0);
        jobMapper.upsert(taskId, UPSERT, hash, runAfter);
    }

    private void process(TaskSemanticIndexJob job) {
        try {
            if (DELETE.equals(job.getOperation())) {
                semanticSearchService.deletePoints(List.of(job.getTaskId()));
            } else {
                Task task = taskMapper.selectById(job.getTaskId());
                if (task == null) {
                    semanticSearchService.deletePoints(List.of(job.getTaskId()));
                } else {
                    String currentHash = semanticSearchService.contentHash(task);
                    if (!currentHash.equals(job.getContentHash())) {
                        enqueueUpsert(job.getTaskId());
                        return;
                    }
                    semanticSearchService.upsert(task);
                    taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                            .eq(Task::getId, task.getId()).set(Task::getSemanticIndexHash, currentHash));
                }
            }
            jobMapper.deleteIfVersion(job.getTaskId(), job.getVersion());
        } catch (RuntimeException e) {
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            long delaySeconds = Math.min(300, 1L << Math.min(attempts + 1, 8));
            jobMapper.rescheduleIfVersion(job.getTaskId(), job.getVersion(), LocalDateTime.now().plusSeconds(delaySeconds));
            log.error("任务语义索引处理失败 taskId={} operation={}", job.getTaskId(), job.getOperation(), e);
        }
    }
}
