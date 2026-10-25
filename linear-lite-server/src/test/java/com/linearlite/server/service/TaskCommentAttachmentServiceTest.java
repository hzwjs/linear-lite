package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskAttachment;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskAttachmentMapper;
import com.linearlite.server.mapper.TaskCommentAttachmentMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCommentAttachmentServiceTest {
    @Mock private TaskPermissionGuard permissions;
    @Mock private TaskAttachmentMapper taskAttachments;
    @Mock private TaskCommentAttachmentMapper commentAttachments;
    @Mock private TaskMapper tasks;
    @Mock private ObjectStorageService storage;
    private TaskCommentAttachmentService service;
    private Task task;

    @BeforeEach
    void setUp() {
        service = new TaskCommentAttachmentService(permissions, taskAttachments, commentAttachments, tasks, storage);
        task = new Task(); task.setId(4L); task.setTaskKey("TEST-4"); task.setProjectId(9L);
        when(permissions.requireTaskAccessByKey("TEST-4", 7L)).thenReturn(task);
    }

    @Test
    void uploadsValidatedImageToPrivateCommentObjectKey() throws Exception {
        byte[] png = png();
        when(storage.uploadTaskCommentImage(any(), any(Long.class), any(), any(), any(Long.class), any(Long.class)))
                .thenReturn("task-comment-attachments/4/image.png");

        TaskCommentImageDraft draft = service.upload("TEST-4", 7L, "image1", "coupon.png", "image/png", png);

        assertEquals("task-comment-attachments/4/image.png", draft.objectKey());
        assertEquals(null, draft.sourceAttachmentId());
        verify(storage).uploadTaskCommentImage(any(), any(Long.class), any(), any(), any(Long.class), any(Long.class));
    }

    @Test
    void referencesOnlyAnOwnedDecodableImageAttachment() throws Exception {
        byte[] png = png();
        TaskAttachment source = new TaskAttachment(); source.setId(31L); source.setTaskId(4L);
        source.setObjectKey("attachments/4/coupon.png"); source.setFileName("coupon.png");
        source.setContentType("image/png"); source.setFileSize((long) png.length);
        when(taskAttachments.selectOne(any(LambdaQueryWrapper.class))).thenReturn(source);
        when(storage.openObjectStreamByKey(source.getObjectKey())).thenReturn(new ByteArrayInputStream(png));

        TaskCommentImageDraft draft = service.reference("TEST-4", 7L, "coupon", 31L);

        assertEquals(31L, draft.sourceAttachmentId());
        assertEquals("image/png", draft.contentType());
    }

    @Test
    void rejectsForeignAttachmentAndNonImageMime() {
        when(taskAttachments.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.reference("TEST-4", 7L, "coupon", 31L));

        TaskAttachment source = new TaskAttachment(); source.setId(31L); source.setTaskId(4L);
        source.setContentType("application/pdf"); source.setFileSize(12L);
        when(taskAttachments.selectOne(any(LambdaQueryWrapper.class))).thenReturn(source);
        assertThrows(IllegalArgumentException.class, () -> service.reference("TEST-4", 7L, "coupon", 31L));
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
