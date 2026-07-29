package com.linearlite.server.service;

import com.linearlite.server.config.SemanticSearchProperties;
import com.linearlite.server.entity.ProjectContentSemanticIndexJob;
import com.linearlite.server.entity.SearchableProjectContent;
import com.linearlite.server.mapper.ProjectContentSearchMapper;
import com.linearlite.server.mapper.ProjectContentSemanticIndexJobMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectContentSemanticIndexQueueServiceTest {
    @Test
    void indexesDocumentThroughUnifiedQueue() {
        SemanticSearchProperties properties = enabledProperties();
        ProjectContentSemanticIndexJobMapper jobs = mock(ProjectContentSemanticIndexJobMapper.class);
        ProjectContentSearchMapper contents = mock(ProjectContentSearchMapper.class);
        ProjectContentSemanticSearchService semantic = mock(ProjectContentSemanticSearchService.class);
        SearchableProjectContent document = content("DOCUMENT", 12L);
        ProjectContentSemanticIndexJob job = job("DOCUMENT", 12L, "hash");
        when(contents.selectDocument(12L)).thenReturn(document);
        when(semantic.contentHash(document)).thenReturn("hash");
        when(jobs.selectDue(any(), eq(20))).thenReturn(List.of(job));

        service(properties, jobs, contents, semantic).processDueJobs();

        verify(semantic).upsert(document);
        verify(jobs).deleteIfVersion("DOCUMENT", 12L, 1L);
    }

    @Test
    void staleJobIsRequeuedWithoutWritingOldContent() {
        SemanticSearchProperties properties = enabledProperties();
        ProjectContentSemanticIndexJobMapper jobs = mock(ProjectContentSemanticIndexJobMapper.class);
        ProjectContentSearchMapper contents = mock(ProjectContentSearchMapper.class);
        ProjectContentSemanticSearchService semantic = mock(ProjectContentSemanticSearchService.class);
        SearchableProjectContent task = content("TASK", 3L);
        when(contents.selectTask(3L)).thenReturn(task);
        when(semantic.contentHash(task)).thenReturn("new-hash");
        when(jobs.selectDue(any(), eq(20))).thenReturn(List.of(job("TASK", 3L, "old-hash")));

        service(properties, jobs, contents, semantic).processDueJobs();

        verify(jobs).upsert(eq("TASK"), eq(3L), eq("UPSERT"), eq("new-hash"), any(LocalDateTime.class));
        verify(semantic, never()).upsert(task);
    }

    private static ProjectContentSemanticIndexQueueService service(
            SemanticSearchProperties properties, ProjectContentSemanticIndexJobMapper jobs,
            ProjectContentSearchMapper contents, ProjectContentSemanticSearchService semantic) {
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

    private static ProjectContentSemanticIndexJob job(String type, Long id, String hash) {
        ProjectContentSemanticIndexJob job = new ProjectContentSemanticIndexJob();
        job.setContentType(type);
        job.setResourceId(id);
        job.setOperation("UPSERT");
        job.setContentHash(hash);
        job.setVersion(1L);
        job.setAttempts(0);
        return job;
    }
}
