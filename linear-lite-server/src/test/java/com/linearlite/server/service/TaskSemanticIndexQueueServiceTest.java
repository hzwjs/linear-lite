package com.linearlite.server.service;

import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.Task;
import com.linearlite.server.mapper.TaskMapper;
import com.linearlite.server.mapper.TaskSemanticIndexJobMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskSemanticIndexQueueServiceTest {
    @Mock private TaskSemanticIndexJobMapper jobMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskSemanticSearchService semanticSearchService;

    @Test
    void enqueueUpsertSkipsContentAlreadyIndexed() {
        Task task = task(7L, "same-hash");
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(semanticSearchService.contentHash(task)).thenReturn("same-hash");

        service().enqueueUpsert(7L);

        verify(jobMapper, never()).upsert(any(), any(), any(), any());
    }

    @Test
    void enqueueUpsertDelaysChangedContentForDebounceWindow() {
        Task task = task(8L, "old-hash");
        when(taskMapper.selectById(8L)).thenReturn(task);
        when(semanticSearchService.contentHash(task)).thenReturn("new-hash");
        LocalDateTime before = LocalDateTime.now().plusSeconds(29);

        service().enqueueUpsert(8L);

        ArgumentCaptor<LocalDateTime> due = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobMapper).upsert(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.eq("UPSERT"),
                org.mockito.ArgumentMatchers.eq("new-hash"), due.capture());
        assertEquals(true, due.getValue().isAfter(before));
    }

    private TaskSemanticIndexQueueService service() {
        SemanticSearchProperties properties = new SemanticSearchProperties();
        properties.setEnabled(true);
        properties.setIndexDebounceSeconds(30);
        return new TaskSemanticIndexQueueService(properties, jobMapper, taskMapper, semanticSearchService);
    }

    private static Task task(Long id, String indexedHash) {
        Task task = new Task();
        task.setId(id);
        task.setSemanticIndexHash(indexedHash);
        return task;
    }
}
