package com.linearlite.server.service;

import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.ProjectContentSemanticIndexJob;
import com.linearlite.server.entity.SearchableProjectContent;
import com.linearlite.server.mapper.ProjectContentSearchMapper;
import com.linearlite.server.mapper.ProjectContentSemanticIndexJobMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectContentSemanticIndexQueueServiceTest {
    @Test
    void indexesClaimedDocumentAndDeletesOnlySameGeneration() {
        SemanticSearchProperties properties = enabledProperties();
        ProjectContentSemanticIndexJobMapper jobs = mock(ProjectContentSemanticIndexJobMapper.class);
        ProjectContentSearchMapper contents = mock(ProjectContentSearchMapper.class);
        ProjectContentSearchIndex semantic = mock(ProjectContentSearchIndex.class);
        SearchableProjectContent document = content("DOCUMENT", 12L);
        ProjectContentSemanticIndexJob job = job("DOCUMENT", 12L, "UPSERT", 4L, 0);
        when(contents.selectDocument(12L)).thenReturn(document);
        when(jobs.selectDue(any(), eq(20))).thenReturn(List.of(job));
        when(jobs.claimLease(eq("DOCUMENT"), eq(12L), eq(4L), any(), any())).thenReturn(1);

        service(properties, jobs, contents, semantic).processDueJobs();

        verify(semantic).upsert(document);
        verify(jobs).deleteIfGeneration("DOCUMENT", 12L, 4L);
    }

    @Test
    void skipsJobWhenAnotherInstanceOwnsLease() {
        SemanticSearchProperties properties = enabledProperties();
        ProjectContentSemanticIndexJobMapper jobs = mock(ProjectContentSemanticIndexJobMapper.class);
        ProjectContentSearchMapper contents = mock(ProjectContentSearchMapper.class);
        ProjectContentSearchIndex semantic = mock(ProjectContentSearchIndex.class);
        ProjectContentSemanticIndexJob job = job("TASK", 3L, "UPSERT", 2L, 0);
        when(jobs.selectDue(any(), eq(20))).thenReturn(List.of(job));
        when(jobs.claimLease(eq("TASK"), eq(3L), eq(2L), any(), any())).thenReturn(0);

        service(properties, jobs, contents, semantic).processDueJobs();

        verify(contents, never()).selectTask(3L);
        verify(semantic, never()).upsert(any());
        verify(jobs, never()).deleteIfGeneration(any(), any(), any());
    }

    @Test
    void missingSourceUsesDeleteWithoutFallback() {
        SemanticSearchProperties properties = enabledProperties();
        ProjectContentSemanticIndexJobMapper jobs = mock(ProjectContentSemanticIndexJobMapper.class);
        ProjectContentSearchMapper contents = mock(ProjectContentSearchMapper.class);
        ProjectContentSearchIndex semantic = mock(ProjectContentSearchIndex.class);
        ProjectContentSemanticIndexJob job = job("TASK", 8L, "UPSERT", 3L, 0);
        when(jobs.selectDue(any(), eq(20))).thenReturn(List.of(job));
        when(jobs.claimLease(eq("TASK"), eq(8L), eq(3L), any(), any())).thenReturn(1);
        when(contents.selectTask(8L)).thenReturn(null);

        service(properties, jobs, contents, semantic).processDueJobs();

        verify(semantic).delete("TASK", 8L);
        verify(semantic, never()).upsert(any());
        verify(jobs).deleteIfGeneration("TASK", 8L, 3L);
    }

    @Test
    void failureReschedulesOnlySameGenerationAndReleasesLease() {
        SemanticSearchProperties properties = enabledProperties();
        ProjectContentSemanticIndexJobMapper jobs = mock(ProjectContentSemanticIndexJobMapper.class);
        ProjectContentSearchMapper contents = mock(ProjectContentSearchMapper.class);
        ProjectContentSearchIndex semantic = mock(ProjectContentSearchIndex.class);
        SearchableProjectContent task = content("TASK", 5L);
        ProjectContentSemanticIndexJob job = job("TASK", 5L, "UPSERT", 9L, 2);
        when(jobs.selectDue(any(), eq(20))).thenReturn(List.of(job));
        when(jobs.claimLease(eq("TASK"), eq(5L), eq(9L), any(), any())).thenReturn(1);
        when(contents.selectTask(5L)).thenReturn(task);
        doThrow(new IllegalStateException("qdrant unavailable")).when(semantic).upsert(task);

        service(properties, jobs, contents, semantic).processDueJobs();

        verify(jobs).rescheduleIfGeneration(eq("TASK"), eq(5L), eq(9L), any(LocalDateTime.class));
        verify(jobs, never()).deleteIfGeneration(any(), any(), any());
    }

    @Test
    void synchronousListenersRequireExistingBusinessTransaction() throws Exception {
        Transactional changed = ProjectContentSemanticIndexQueueService.class
                .getMethod("onContentChanged", ProjectContentSemanticIndexRequestedEvent.class)
                .getAnnotation(Transactional.class);
        Transactional deleted = ProjectContentSemanticIndexQueueService.class
                .getMethod("onContentDeleted", ProjectContentSemanticDeleteRequestedEvent.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.MANDATORY, changed.propagation());
        assertEquals(Propagation.MANDATORY, deleted.propagation());
    }

    @Test
    void newerGenerationPreservesActiveLeaseSoWorkersCannotOverwriteOutOfOrder() throws Exception {
        Insert insert = ProjectContentSemanticIndexJobMapper.class
                .getMethod("upsert", String.class, Long.class, String.class, LocalDateTime.class)
                .getAnnotation(Insert.class);
        String sql = String.join(" ", insert.value());

        assertFalse(sql.contains("lease_until = NULL"));
    }

    private static ProjectContentSemanticIndexQueueService service(
            SemanticSearchProperties properties, ProjectContentSemanticIndexJobMapper jobs,
            ProjectContentSearchMapper contents, ProjectContentSearchIndex semantic) {
        return new ProjectContentSemanticIndexQueueService(properties, jobs, contents, semantic);
    }

    private static SemanticSearchProperties enabledProperties() {
        SemanticSearchProperties properties = new SemanticSearchProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static SearchableProjectContent content(String type, Long id) {
        SearchableProjectContent content = new SearchableProjectContent();
        content.setContentType(type);
        content.setNumericId(id);
        content.setResourceId(type.equals("TASK") ? "ENG-" + id : id.toString());
        content.setProjectId(7L);
        content.setTitle("Title");
        content.setSourceContent("[]");
        return content;
    }

    private static ProjectContentSemanticIndexJob job(
            String type, Long id, String operation, Long generation, int attempts) {
        ProjectContentSemanticIndexJob job = new ProjectContentSemanticIndexJob();
        job.setContentType(type);
        job.setResourceId(id);
        job.setOperation(operation);
        job.setGeneration(generation);
        job.setAttempts(attempts);
        return job;
    }
}
