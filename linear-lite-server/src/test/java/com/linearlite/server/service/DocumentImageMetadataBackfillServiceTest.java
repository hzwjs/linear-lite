package com.linearlite.server.service;

import com.linearlite.server.entity.ProjectDocumentAttachment;
import com.linearlite.server.mapper.ProjectDocumentAttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentImageMetadataBackfillServiceTest {

    @Mock private ProjectDocumentAttachmentMapper attachmentMapper;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private DocumentImageProcessor imageProcessor;

    private DocumentImageMetadataBackfillService service;

    @BeforeEach
    void setUp() {
        service = new DocumentImageMetadataBackfillService(
                attachmentMapper, objectStorageService, imageProcessor);
    }

    @Test
    void backfillsDimensionsAndThumbnail() throws Exception {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment(31L, "key")));
        when(objectStorageService.openObjectStreamByKey("key"))
                .thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
        when(imageProcessor.analyze(any(byte[].class))).thenReturn(
                new DocumentImageProcessor.ImageMetadata(100, 50, "image/png", "image/jpeg", new byte[] { 9, 9 }));
        when(objectStorageService.uploadProjectDocumentThumbnail(any(), eq("image/jpeg"), eq(7L), eq(11L)))
                .thenReturn("document-attachments/7/11/thumbnails/x.jpg");

        assertEquals(1, service.backfill());
        verify(attachmentMapper).update(isNull(), any());
    }

    @Test
    void skipsAttachmentsThatAlreadyHaveMetadata() {
        ProjectDocumentAttachment attachment = attachment(31L, "key");
        attachment.setWidth(100);
        attachment.setHeight(50);
        attachment.setThumbnailObjectKey("thumb");
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment));

        assertEquals(0, service.backfill());
        verifyNoInteractions(objectStorageService, imageProcessor);
    }

    @Test
    void keepsGoingWhenOneImageCannotBeRead() throws Exception {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment(31L, "bad"), attachment(32L, "good")));
        when(objectStorageService.openObjectStreamByKey("bad")).thenThrow(new IllegalStateException("missing"));
        when(objectStorageService.openObjectStreamByKey("good"))
                .thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
        when(imageProcessor.analyze(any(byte[].class))).thenReturn(
                new DocumentImageProcessor.ImageMetadata(10, 10, "image/png", null, null));

        assertEquals(1, service.backfill());
        verify(attachmentMapper).update(isNull(), any());
    }

    private ProjectDocumentAttachment attachment(Long id, String objectKey) {
        ProjectDocumentAttachment attachment = new ProjectDocumentAttachment();
        attachment.setId(id);
        attachment.setProjectId(7L);
        attachment.setDocumentId(11L);
        attachment.setObjectKey(objectKey);
        attachment.setContentType("image/png");
        return attachment;
    }
}
