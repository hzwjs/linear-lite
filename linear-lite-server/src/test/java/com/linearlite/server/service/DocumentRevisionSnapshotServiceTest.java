package com.linearlite.server.service;

import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
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
class DocumentRevisionSnapshotServiceTest {
    @Mock private ProjectDocumentRevisionMapper revisionMapper;

    @Test
    void forcedCollaborationCommitStoresProjectionSnapshot() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        ProjectDocument saved = document(10L, 2L, "第二版", 7L);
        when(revisionMapper.selectOne(any())).thenReturn(null);

        service.captureForced(saved, 7L, "[]");

        ArgumentCaptor<ProjectDocumentRevision> captor = ArgumentCaptor.forClass(ProjectDocumentRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getVersion());
        assertEquals("第二版", captor.getValue().getTitle());
        assertEquals("[]", captor.getValue().getContent());
    }

    @Test
    void existingSnapshotWithSameProjectionIsNotDuplicated() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        ProjectDocument saved = document(10L, 2L, "第二版", 7L);
        ProjectDocumentRevision existing = revision(10L, 2L, LocalDateTime.now());
        existing.setTitle("第二版");
        existing.setContent("[]");
        existing.setContentFormat("BLOCKNOTE");
        when(revisionMapper.selectOne(any())).thenReturn(existing);

        service.captureForced(saved, 7L, "[]");

        verify(revisionMapper, never()).insert(any(ProjectDocumentRevision.class));
    }

    @Test
    void repeatedCaptureDoesNotCreateDuplicateVersion() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        ProjectDocument document = document(10L, 1L, "初始", 7L);
        ProjectDocumentRevision existing = revision(10L, 1L, LocalDateTime.now());
        existing.setTitle("初始");
        existing.setContent("[]");
        existing.setContentFormat("BLOCKNOTE");
        when(revisionMapper.selectOne(any())).thenReturn(existing);

        service.captureInitial(document, 7L, "[]");

        verify(revisionMapper, never()).insert(any(ProjectDocumentRevision.class));
    }

    private static ProjectDocument document(Long id, Long version, String title, Long editorId) {
        ProjectDocument document = new ProjectDocument();
        document.setId(id);
        document.setVersion(version);
        document.setTitle(title);
        document.setContentFormat("BLOCKNOTE");
        document.setLastEditorId(editorId);
        document.setUpdatedAt(LocalDateTime.now());
        return document;
    }

    private static ProjectDocumentRevision revision(Long documentId, Long version, LocalDateTime createdAt) {
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setDocumentId(documentId);
        revision.setVersion(version);
        revision.setCreatedAt(createdAt);
        return revision;
    }
    @Test
    void htmlExplicitSaveCapturesRevisionWithoutWaitingForIdleThreshold() {
        ProjectDocument saved = document(11L, 2L, "HTML", 7L);
        saved.setContentFormat("HTML");
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        service.captureForced(saved, 7L, "<p>版本二</p>");
        var captor = org.mockito.ArgumentCaptor.forClass(com.linearlite.server.entity.ProjectDocumentRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertEquals("HTML", captor.getValue().getContentFormat());
        assertEquals("<p>版本二</p>", captor.getValue().getContent());
    }

    @Test
    void successiveAutomaticSavesLeaveOnlyTheDueSnapshotAndTheIdleSnapshot() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        when(revisionMapper.isActiveSnapshotDue(12L)).thenReturn(false, false, true, false);
        ProjectDocument document = document(12L, 2L, "标题", 7L);
        service.captureIfDue(document, 7L, "[2]");
        document.setVersion(3L);
        service.captureIfDue(document, 7L, "[3]");
        verify(revisionMapper, never()).insert(any(ProjectDocumentRevision.class));

        document.setVersion(4L);
        service.captureIfDue(document, 7L, "[4]");
        document.setVersion(5L);
        service.captureIfDue(document, 7L, "[5]");
        service.captureIfChanged(document, 7L, "[5]");

        var captor = ArgumentCaptor.forClass(ProjectDocumentRevision.class);
        verify(revisionMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(java.util.List.of(4L, 5L), captor.getAllValues().stream()
                .map(ProjectDocumentRevision::getVersion).toList());
    }

    @Test
    void unchangedContentAndTitleDoNotCreateAnotherSnapshotAtAnIdleBoundary() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        ProjectDocument document = document(12L, 6L, "标题", 7L);
        ProjectDocumentRevision previous = revision(12L, 5L, LocalDateTime.now());
        previous.setTitle("标题");
        previous.setContentFormat("BLOCKNOTE");
        previous.setContent("[]");
        when(revisionMapper.selectOne(any())).thenReturn(previous);

        service.captureIfChanged(document, 7L, "[]");

        verify(revisionMapper, never()).insert(any(ProjectDocumentRevision.class));
    }

    @Test
    void dueTitleVersionUsesBodyProjectionFromCollaborationSnapshot() {
        ProjectDocument document = document(12L, 3L, "新标题", 7L);
        String snapshotContent = "[{\"type\":\"paragraph\",\"content\":\"当前正文\"}]";
        when(revisionMapper.selectOne(any())).thenReturn(null);
        when(revisionMapper.isActiveSnapshotDue(12L)).thenReturn(true);

        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(revisionMapper);
        service.captureIfDue(document, 7L, snapshotContent);

        ArgumentCaptor<ProjectDocumentRevision> captor = ArgumentCaptor.forClass(ProjectDocumentRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertEquals(3L, captor.getValue().getVersion());
        assertEquals("新标题", captor.getValue().getTitle());
        assertEquals(snapshotContent, captor.getValue().getContent());
    }

}
