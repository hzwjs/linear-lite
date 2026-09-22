package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentImageOrphanCleanupServiceTest {

    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectDocumentAttachmentMapper attachmentMapper;
    @Mock private ObjectStorageService objectStorageService;

    private DocumentImageOrphanCleanupService service;

    @BeforeEach
    void setUp() {
        initTableInfo(ProjectDocumentAttachment.class);
        initTableInfo(ProjectDocument.class);
        initTableInfo(ProjectDocumentRevision.class);
        service = new DocumentImageOrphanCleanupService(
                documentMapper, revisionMapper, attachmentMapper, objectStorageService, new ObjectMapper());
    }

    private void initTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "document-image-cleanup-test"),
                    entityType);
        }
    }

    @Test
    void candidateQueryOnlySelectsImages() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ProjectDocumentAttachment>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(attachmentMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("content_type LIKE"));
    }

    @Test
    void invalidDocumentJsonSkipsEveryDeletionForProject() {
        ProjectDocumentAttachment candidate = imageAttachment(31L, 7L, 11L);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setContentJson("not-json");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectList(any())).thenReturn(List.of(document));

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        verify(objectStorageService, never()).deleteObjectByKey(any());
        verify(attachmentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void referencedImageIsRetained() {
        ProjectDocumentAttachment candidate = imageAttachment(31L, 7L, 11L);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setContentJson("[{\"type\":\"documentImage\",\"props\":{\"imageAssetId\":31}}]");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        verify(objectStorageService, never()).deleteObjectByKey(any());
        verify(attachmentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void legacyImageReferencePreventsCleanupInsteadOfParsingItsUrl() {
        ProjectDocumentAttachment candidate = imageAttachment(31L, 7L, 11L);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setContentJson("[{\"type\":\"image\",\"props\":{\"url\":\"/api/project-documents/11/attachments/31/download\"}}]");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectList(any())).thenReturn(List.of(document));

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        verify(revisionMapper, never()).selectList(any());
        verify(objectStorageService, never()).deleteObjectByKey(any());
        verify(attachmentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void legacyImageReferenceInHistoryPreventsCleanup() {
        ProjectDocumentAttachment candidate = imageAttachment(31L, 7L, 11L);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setContentJson("[]");
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setId(41L);
        revision.setDocumentId(11L);
        revision.setContentJson("[{\"type\":\"image\",\"props\":{\"url\":\"/api/project-documents/11/attachments/31/download\"}}]");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of(revision));

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        verify(objectStorageService, never()).deleteObjectByKey(any());
        verify(attachmentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void documentImageWithUrlFallbackPreventsCleanup() {
        ProjectDocumentAttachment candidate = imageAttachment(31L, 7L, 11L);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setContentJson("[{\"type\":\"documentImage\",\"props\":{\"imageAssetId\":31,\"url\":\"/api/project-documents/11/attachments/32/download\"}}]");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectList(any())).thenReturn(List.of(document));

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        verify(objectStorageService, never()).deleteObjectByKey(any());
        verify(attachmentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void invalidRevisionJsonSkipsEveryDeletionForProject() {
        ProjectDocumentAttachment candidate = imageAttachment(31L, 7L, 11L);
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(7L);
        document.setContentJson("[]");
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setId(41L);
        revision.setDocumentId(11L);
        revision.setContentJson("not-json");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(candidate));
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        when(revisionMapper.selectList(any())).thenReturn(List.of(revision));

        assertEquals(0, service.cleanup(Duration.ofHours(24)));

        verify(objectStorageService, never()).deleteObjectByKey(any());
        verify(attachmentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    private ProjectDocumentAttachment imageAttachment(Long id, Long projectId, Long documentId) {
        ProjectDocumentAttachment attachment = new ProjectDocumentAttachment();
        attachment.setId(id);
        attachment.setProjectId(projectId);
        attachment.setDocumentId(documentId);
        attachment.setContentType("image/png");
        attachment.setObjectKey("document-attachments/7/11/image.png");
        attachment.setCreatedAt(LocalDateTime.now().minusDays(2));
        return attachment;
    }
}
