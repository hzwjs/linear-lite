package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentImageContentMigrationServiceTest {

    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectDocumentAttachmentMapper attachmentMapper;

    private DocumentImageContentMigrationService service;

    @BeforeEach
    void setUp() {
        service = new DocumentImageContentMigrationService(
                documentMapper, revisionMapper, attachmentMapper, new ObjectMapper());
    }

    @Test
    void migratesImageBlockUrlToAssetId() {
        ProjectDocument document = document(11L, "[{\"type\":\"image\",\"props\":{\"url\":"
                + "\"/api/project-documents/11/attachments/31/download\",\"caption\":\"图\"}}]");
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of());
        when(attachmentMapper.selectById(31L)).thenReturn(imageAttachment(31L, 11L));

        DocumentImageContentMigrationService.MigrationReport report = service.migrate();

        assertFalse(report.aborted());
        assertEquals(1, report.documentsMigrated());
        verify(documentMapper).update(isNull(), any());
    }

    @Test
    void abortsWithoutWritingWhenImageUrlCannotBeResolved() {
        ProjectDocument document = document(11L, "[{\"type\":\"image\",\"props\":{\"url\":"
                + "\"/api/project-documents/11/attachments/404/download\"}}]");
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of());
        when(attachmentMapper.selectById(404L)).thenReturn(null);

        DocumentImageContentMigrationService.MigrationReport report = service.migrate();

        assertTrue(report.aborted());
        assertEquals(0, report.documentsMigrated());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void rejectsImageUrlPointingToAnotherDocument() {
        ProjectDocument document = document(11L, "[{\"type\":\"image\",\"props\":{\"url\":"
                + "\"/api/project-documents/99/attachments/31/download\"}}]");
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of());

        DocumentImageContentMigrationService.MigrationReport report = service.migrate();

        assertTrue(report.aborted());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void leavesExternalImageUrlsUntouched() {
        ProjectDocument document = document(11L, "[{\"type\":\"image\",\"props\":{\"url\":\"https://cdn.example/a.png\"}}]");
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of());

        DocumentImageContentMigrationService.MigrationReport report = service.migrate();

        assertFalse(report.aborted());
        assertEquals(0, report.documentsMigrated());
        verify(documentMapper, never()).update(any(), any());
    }

    private ProjectDocument document(Long id, String contentJson) {
        ProjectDocument document = new ProjectDocument();
        document.setId(id);
        document.setContentJson(contentJson);
        return document;
    }

    private ProjectDocumentAttachment imageAttachment(Long id, Long documentId) {
        ProjectDocumentAttachment attachment = new ProjectDocumentAttachment();
        attachment.setId(id);
        attachment.setDocumentId(documentId);
        attachment.setContentType("image/png");
        return attachment;
    }
}
