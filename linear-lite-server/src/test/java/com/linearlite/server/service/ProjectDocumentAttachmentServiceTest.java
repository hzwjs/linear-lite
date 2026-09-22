package com.linearlite.server.service;

import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentAttachmentServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentAttachmentMapper attachmentMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private DocumentImageProcessor imageProcessor;

    private ProjectDocumentAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDocumentAttachmentService(
                documentMapper, attachmentMapper, accessGuard, objectStorageService, imageProcessor, 50L * 1024 * 1024);
    }

    @Test
    void uploadPersistsHashAndUsesAuthenticatedDownloadUrl() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf-content".getBytes());
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(null, attachment(31L, 7L, 11L, "outline:doc:file"));
        when(objectStorageService.uploadProjectDocumentAttachment(any(java.io.InputStream.class), eq((long) "pdf-content".getBytes().length), any(), any(), eq(7L), eq(11L), eq(50L * 1024 * 1024)))
                .thenReturn(new ImageUploadResponse("https://unused.example/report.pdf", "document-attachments/7/11/report.pdf"));
        when(attachmentMapper.insert(any(ProjectDocumentAttachment.class))).thenAnswer(invocation -> {
            ProjectDocumentAttachment value = invocation.getArgument(0);
            value.setId(31L);
            return 1;
        });

        var response = service.upload(11L, file, "outline:doc:file", 5L);

        verify(accessGuard).requireMember(7L, 5L);
        verify(documentMapper).lockProjectDocumentMutations(7L);
        assertEquals("/api/project-documents/11/attachments/31/download", response.url());
        assertEquals("outline:doc:file", response.sourceId());
    }

    @Test
    void rerunWithSameSourceAndHashDoesNotUploadAgain() {
        ProjectDocument document = document(11L, 7L);
        byte[] bytes = "same".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "same.xml", "application/xml", bytes);
        ProjectDocumentAttachment existing = attachment(31L, 7L, 11L, "outline:EUEFsRqmJ4:settings.xml");
        existing.setFileSize((long) bytes.length);
        existing.setSha256("0967115f2813a3541eaef77de9d9d5773f1c0c04314b0bbfe4ff3b3b1c55b5d5");
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(existing);

        var response = service.upload(11L, file, existing.getSourceId(), 5L);

        assertEquals(31L, response.id());
        verify(objectStorageService, never()).uploadProjectDocumentAttachment(any(java.io.InputStream.class), anyLong(), any(), any(), anyLong(), anyLong(), anyLong());
        verify(attachmentMapper, never()).insert(any());
    }

    @Test
    void uploadRejectsFileOverConfiguredDocumentLimit() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[8]);
        service = new ProjectDocumentAttachmentService(
                documentMapper, attachmentMapper, accessGuard, objectStorageService, imageProcessor, 4L);
        when(documentMapper.selectById(11L)).thenReturn(document);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(11L, file, "outline:large", 5L));

        assertEquals("文档附件超过大小限制", error.getMessage());
        verify(objectStorageService, never()).uploadProjectDocumentAttachment(any(java.io.InputStream.class), anyLong(), any(), any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void uploadDeletesObjectWhenMetadataPersistenceFails() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf".getBytes());
        String objectKey = "document-attachments/7/11/report.pdf";
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(null);
        when(objectStorageService.uploadProjectDocumentAttachment(any(java.io.InputStream.class), eq(3L), any(), any(), eq(7L), eq(11L), eq(50L * 1024 * 1024)))
                .thenReturn(new ImageUploadResponse("https://unused.example/report.pdf", objectKey));
        IllegalStateException persistenceFailure = new IllegalStateException("insert failed");
        when(attachmentMapper.insert(any(ProjectDocumentAttachment.class))).thenThrow(persistenceFailure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.upload(11L, file, "outline:doc:report.pdf", 5L));

        assertEquals("insert failed", thrown.getMessage());
        verify(objectStorageService).deleteObjectByKey(objectKey);
    }

    @Test
    void uploadPreservesPersistenceFailureWhenCompensationAlsoFails() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf".getBytes());
        String objectKey = "document-attachments/7/11/report.pdf";
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(null);
        when(objectStorageService.uploadProjectDocumentAttachment(any(java.io.InputStream.class), eq(3L), any(), any(), eq(7L), eq(11L), eq(50L * 1024 * 1024)))
                .thenReturn(new ImageUploadResponse("https://unused.example/report.pdf", objectKey));
        IllegalStateException persistenceFailure = new IllegalStateException("insert failed");
        IllegalStateException compensationFailure = new IllegalStateException("delete failed");
        when(attachmentMapper.insert(any(ProjectDocumentAttachment.class))).thenThrow(persistenceFailure);
        org.mockito.Mockito.doThrow(compensationFailure)
                .when(objectStorageService).deleteObjectByKey(objectKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.upload(11L, file, "outline:doc:report.pdf", 5L));

        assertEquals("insert failed", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("delete failed", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void outerRollbackDeletesNewObjectAndThumbnail() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[] {1, 2, 3});
        String objectKey = "document-attachments/7/11/image.png";
        String thumbnailKey = "document-attachments/7/11/thumbnails/image.png";
        ProjectDocumentAttachment persisted = attachment(31L, 7L, 11L, null);
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(persisted);
        when(objectStorageService.uploadProjectDocumentAttachment(any(java.io.InputStream.class), eq(3L), any(), any(), eq(7L), eq(11L), eq(50L * 1024 * 1024)))
                .thenReturn(new ImageUploadResponse("https://unused.example/image.png", objectKey));
        when(objectStorageService.uploadProjectDocumentThumbnail(any(byte[].class), eq("image/jpeg"), eq(7L), eq(11L)))
                .thenReturn(thumbnailKey);
        when(imageProcessor.analyzeStream(any(java.io.InputStream.class)))
                .thenReturn(new DocumentImageProcessor.ImageMetadata(1, 1, "image/png", "image/jpeg", new byte[] {4}));
        when(attachmentMapper.insert(any(ProjectDocumentAttachment.class))).thenAnswer(invocation -> {
            ProjectDocumentAttachment value = invocation.getArgument(0);
            value.setId(31L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(11L, file, null, 5L);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            verify(objectStorageService, never()).deleteObjectByKey(any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(objectStorageService).deleteObjectByKey(objectKey);
            verify(objectStorageService).deleteObjectByKey(thumbnailKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void existingSourceResourceIsNotRegisteredForRollbackDeletion() {
        ProjectDocument document = document(11L, 7L);
        byte[] bytes = "same".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "same.png", "image/png", bytes);
        ProjectDocumentAttachment existing = attachment(31L, 7L, 11L, "mcp-image:source");
        existing.setFileSize((long) bytes.length);
        existing.setSha256("0967115f2813a3541eaef77de9d9d5773f1c0c04314b0bbfe4ff3b3b1c55b5d5");
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(existing);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(11L, file, existing.getSourceId(), 5L);
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
            verify(objectStorageService, never()).deleteObjectByKey(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void committedUploadKeepsNewObject() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});
        ProjectDocumentAttachment persisted = attachment(31L, 7L, 11L, null);
        String objectKey = "document-attachments/7/11/report.pdf";
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(persisted);
        when(objectStorageService.uploadProjectDocumentAttachment(any(java.io.InputStream.class), eq(3L), any(), any(), eq(7L), eq(11L), eq(50L * 1024 * 1024)))
                .thenReturn(new ImageUploadResponse("https://unused.example/report.pdf", objectKey));
        when(attachmentMapper.insert(any(ProjectDocumentAttachment.class))).thenAnswer(invocation -> {
            ProjectDocumentAttachment value = invocation.getArgument(0);
            value.setId(31L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(11L, file, null, 5L);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            verify(objectStorageService, never()).deleteObjectByKey(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void outerRollbackDeletesCopiedObjectForNewAttachmentReference() {
        ProjectDocument target = document(12L, 7L);
        ProjectDocument sourceDocument = document(11L, 7L);
        ProjectDocumentAttachment source = attachment(51L, 7L, 11L, null);
        source.setObjectKey("document-attachments/7/11/source.png");
        source.setContentType("image/png");
        String copiedKey = "document-attachments/7/12/source.png";
        ProjectDocumentAttachment copied = attachment(61L, 7L, 12L, "copied-from:51");
        copied.setObjectKey(copiedKey);
        copied.setContentType("image/png");
        when(documentMapper.selectById(12L)).thenReturn(target);
        when(documentMapper.selectById(11L)).thenReturn(sourceDocument);
        when(attachmentMapper.selectById(51L)).thenReturn(source);
        when(attachmentMapper.selectOne(any())).thenReturn(null, null, copied);
        when(objectStorageService.copyProjectDocumentAttachmentObject(
                eq(source.getObjectKey()), eq(source.getFileName()), eq(7L), eq(12L))).thenReturn(copiedKey);
        when(attachmentMapper.insert(any(ProjectDocumentAttachment.class))).thenAnswer(invocation -> {
            ProjectDocumentAttachment value = invocation.getArgument(0);
            value.setId(61L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cloneToDocument(12L, 51L, 5L);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(objectStorageService).deleteObjectByKey(copiedKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cloneRequiresMembershipInSourceDocumentProject() {
        ProjectDocument target = document(12L, 7L);
        ProjectDocument sourceDocument = document(11L, 8L);
        ProjectDocumentAttachment source = attachment(51L, 8L, 11L, null);
        source.setContentType("image/png");
        when(documentMapper.selectById(12L)).thenReturn(target);
        when(documentMapper.selectById(11L)).thenReturn(sourceDocument);
        when(attachmentMapper.selectById(51L)).thenReturn(source);
        lenient().doThrow(new ForbiddenOperationException("forbidden"))
                .when(accessGuard).requireMember(8L, 5L);

        assertThrows(ForbiddenOperationException.class, () -> service.cloneToDocument(12L, 51L, 5L));

        verify(accessGuard).requireMember(7L, 5L);
        verify(accessGuard).requireMember(8L, 5L);
        verify(objectStorageService, never()).copyProjectDocumentAttachmentObject(any(), any(), anyLong(), anyLong());
    }

    @Test
    void thumbnailEtagChangesWhenThumbnailObjectChanges() {
        ProjectDocument document = document(11L, 7L);
        ProjectDocumentAttachment first = attachment(31L, 7L, 11L, null);
        first.setThumbnailObjectKey("document-attachments/7/11/thumbnails/first.jpg");
        first.setThumbnailContentType("image/jpeg");
        first.setThumbnailFileSize(12L);
        ProjectDocumentAttachment second = attachment(31L, 7L, 11L, null);
        second.setThumbnailObjectKey("document-attachments/7/11/thumbnails/second.jpg");
        second.setThumbnailContentType("image/jpeg");
        second.setThumbnailFileSize(12L);
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(first, second);

        String firstEtag = service.resolveAsset(11L, 31L, first.getSha256(), "thumbnail", 5L).etag();
        String secondEtag = service.resolveAsset(11L, 31L, second.getSha256(), "thumbnail", 5L).etag();

        assertNotEquals(firstEtag, secondEtag);
    }

    private ProjectDocument document(Long id, Long projectId) {
        ProjectDocument value = new ProjectDocument();
        value.setId(id);
        value.setProjectId(projectId);
        return value;
    }

    private ProjectDocumentAttachment attachment(Long id, Long projectId, Long documentId, String sourceId) {
        ProjectDocumentAttachment value = new ProjectDocumentAttachment();
        value.setId(id);
        value.setProjectId(projectId);
        value.setDocumentId(documentId);
        value.setSourceId(sourceId);
        value.setObjectKey("document-attachments/7/11/report.pdf");
        value.setFileName("report.pdf");
        value.setFileSize(11L);
        value.setContentType("application/pdf");
        value.setSha256("0c9a1da08526cc51fae1d6756f2147ae828d56df6025414a6bd38c2ad064fa5a");
        value.setCreatedAt(LocalDateTime.now());
        return value;
    }
}
