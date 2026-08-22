package com.linearlite.server.service;

import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentRevisionSnapshotServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;

    @Test
    void activeEditingCreatesOneSnapshotAfterTenMinutes() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(documentMapper, revisionMapper);
        ProjectDocumentRevision latest = revision(10L, 1L, LocalDateTime.now().minusMinutes(11));
        ProjectDocument saved = document(10L, 2L, "第二版", 7L);
        when(revisionMapper.selectOne(any())).thenReturn(latest, null);

        service.captureAfterUpdate(saved, 7L);

        ArgumentCaptor<ProjectDocumentRevision> captor = ArgumentCaptor.forClass(ProjectDocumentRevision.class);
        verify(revisionMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getVersion());
        assertEquals("第二版", captor.getValue().getTitle());
    }

    @Test
    void idleWorkerCapturesOnlyDocumentsWithoutCurrentVersionSnapshot() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(documentMapper, revisionMapper);
        ProjectDocument candidate = document(10L, 4L, "空闲后", 7L);
        candidate.setUpdatedAt(LocalDateTime.now().minusMinutes(3));
        when(revisionMapper.selectDocumentsNeedingIdleRevision(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectByIdForUpdate(10L)).thenReturn(candidate);
        when(revisionMapper.selectOne(any())).thenReturn(null);

        service.captureIdleRevisions();

        verify(documentMapper).selectByIdForUpdate(10L);
        verify(revisionMapper).insert(any(ProjectDocumentRevision.class));
    }

    @Test
    void repeatedCaptureDoesNotCreateDuplicateVersion() {
        DocumentRevisionSnapshotService service = new DocumentRevisionSnapshotService(documentMapper, revisionMapper);
        ProjectDocument document = document(10L, 1L, "初始", 7L);
        when(revisionMapper.selectOne(any())).thenReturn(revision(10L, 1L, LocalDateTime.now()));

        service.captureInitial(document, 7L);

        verify(revisionMapper, never()).insert(any(ProjectDocumentRevision.class));
    }

    private static ProjectDocument document(Long id, Long version, String title, Long editorId) {
        ProjectDocument document = new ProjectDocument();
        document.setId(id);
        document.setVersion(version);
        document.setTitle(title);
        document.setContentJson("[]");
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
}
