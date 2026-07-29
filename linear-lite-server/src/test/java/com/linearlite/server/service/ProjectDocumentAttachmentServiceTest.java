package com.linearlite.server.service;

import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentAttachmentServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentAttachmentMapper attachmentMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private ObjectStorageService objectStorageService;

    private ProjectDocumentAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDocumentAttachmentService(
                documentMapper, attachmentMapper, accessGuard, objectStorageService, 50L * 1024 * 1024);
    }

    @Test
    void uploadPersistsHashAndUsesAuthenticatedDownloadUrl() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf-content".getBytes());
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(null, attachment(31L, 7L, 11L, "outline:doc:file"));
        when(objectStorageService.uploadProjectDocumentAttachment(file, 7L, 11L, 50L * 1024 * 1024))
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
        verify(objectStorageService, never()).uploadProjectDocumentAttachment(any(), anyLong(), anyLong(), anyLong());
        verify(attachmentMapper, never()).insert(any());
    }

    @Test
    void uploadRejectsFileOverConfiguredDocumentLimit() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[8]);
        service = new ProjectDocumentAttachmentService(
                documentMapper, attachmentMapper, accessGuard, objectStorageService, 4L);
        when(documentMapper.selectById(11L)).thenReturn(document);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(11L, file, "outline:large", 5L));

        assertEquals("文档附件超过大小限制", error.getMessage());
        verify(objectStorageService, never()).uploadProjectDocumentAttachment(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void uploadDeletesObjectWhenMetadataPersistenceFails() {
        ProjectDocument document = document(11L, 7L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf".getBytes());
        String objectKey = "document-attachments/7/11/report.pdf";
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(attachmentMapper.selectOne(any())).thenReturn(null);
        when(objectStorageService.uploadProjectDocumentAttachment(file, 7L, 11L, 50L * 1024 * 1024))
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
        when(objectStorageService.uploadProjectDocumentAttachment(file, 7L, 11L, 50L * 1024 * 1024))
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
