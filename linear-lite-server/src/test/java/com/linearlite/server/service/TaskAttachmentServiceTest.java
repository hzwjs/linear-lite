package com.linearlite.server.service;

import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskAttachment;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.TaskAttachmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskAttachmentServiceTest {

    @Mock
    private TaskQueryService taskQueryService;
    @Mock
    private TaskAttachmentMapper taskAttachmentMapper;
    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private R2StorageProperties r2StorageProperties;
    @Mock
    private MultipartFile multipartFile;

    private TaskAttachmentService taskAttachmentService;

    @BeforeEach
    void setUp() {
        taskAttachmentService = new TaskAttachmentService(
                taskQueryService, taskAttachmentMapper, objectStorageService, r2StorageProperties, 10 * 1024 * 1024L);
    }

    @Test
    void uploadSuccessInsertsAndReturnsResponse() {
        Task task = new Task();
        task.setId(1L);
        task.setTaskKey("ENG-1");
        when(taskQueryService.getByKeyOrThrow("ENG-1", 10L)).thenReturn(task);
        when(multipartFile.getOriginalFilename()).thenReturn("doc.pdf");
        when(multipartFile.getSize()).thenReturn(1024L);
        when(multipartFile.getContentType()).thenReturn("application/pdf");
        when(objectStorageService.uploadAttachment(multipartFile, 1L))
                .thenReturn(new ImageUploadResponse("https://r2.example.com/attachments/1/uuid-doc.pdf", "attachments/1/uuid-doc.pdf"));

        when(taskAttachmentMapper.insert(any(TaskAttachment.class))).thenAnswer(inv -> {
            TaskAttachment att = inv.getArgument(0);
            att.setId(100L);
            att.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        when(taskAttachmentMapper.selectById(100L)).thenAnswer(inv -> {
            TaskAttachment att = new TaskAttachment();
            att.setId(100L);
            att.setTaskId(1L);
            att.setObjectKey("attachments/1/uuid-doc.pdf");
            att.setFileName("doc.pdf");
            att.setFileSize(1024L);
            att.setContentType("application/pdf");
            att.setCreatedAt(LocalDateTime.now());
            return att;
        });

        var response = taskAttachmentService.upload("ENG-1", multipartFile, 10L);

        assertEquals(100L, response.getId());
        assertEquals(1L, response.getTaskId());
        assertEquals("doc.pdf", response.getFileName());
        assertEquals(1024L, response.getFileSize());
        assertEquals("https://r2.example.com/attachments/1/uuid-doc.pdf", response.getUrl());
        verify(taskAttachmentMapper).insert(any(TaskAttachment.class));
        verify(objectStorageService).uploadAttachment(multipartFile, 1L);
    }

    @Test
    void listByTaskKeyReturnsMappedList() {
        Task task = new Task();
        task.setId(1L);
        when(taskQueryService.getByKeyOrThrow("ENG-1", 10L)).thenReturn(task);
        when(r2StorageProperties.getPublicBaseUrl()).thenReturn("https://r2.example.com");
        TaskAttachment att = new TaskAttachment();
        att.setId(1L);
        att.setTaskId(1L);
        att.setObjectKey("attachments/1/abc.pdf");
        att.setFileName("abc.pdf");
        att.setFileSize(100L);
        att.setContentType("application/pdf");
        att.setCreatedAt(LocalDateTime.now());
        when(taskAttachmentMapper.selectList(any())).thenReturn(List.of(att));

        List<com.linearlite.server.dto.TaskAttachmentResponse> list = taskAttachmentService.listByTaskKey("ENG-1", 10L);

        assertEquals(1, list.size());
        assertEquals("abc.pdf", list.get(0).getFileName());
        assertEquals("https://r2.example.com/attachments/1/abc.pdf", list.get(0).getUrl());
    }

    @Test
    void deleteRemovesFromDbAndCallsStorage() {
        Task task = new Task();
        task.setId(1L);
        when(taskQueryService.getByKeyOrThrow("ENG-1", 10L)).thenReturn(task);
        TaskAttachment att = new TaskAttachment();
        att.setId(50L);
        att.setTaskId(1L);
        att.setObjectKey("attachments/1/xyz.pdf");
        when(taskAttachmentMapper.selectOne(any())).thenReturn(att);

        taskAttachmentService.delete("ENG-1", 50L, 10L);

        verify(taskAttachmentMapper).deleteById(50L);
        verify(objectStorageService).deleteObjectByKey("attachments/1/xyz.pdf");
    }

    @Test
    void deleteWhenAttachmentNotFoundThrows() {
        Task task = new Task();
        task.setId(1L);
        when(taskQueryService.getByKeyOrThrow("ENG-1", 10L)).thenReturn(task);
        when(taskAttachmentMapper.selectOne(any())).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
                () -> taskAttachmentService.delete("ENG-1", 999L, 10L));
        verify(taskAttachmentMapper, never()).deleteById(999L);
    }

    @Test
    void uploadWhenNotMemberThrows() {
        when(taskQueryService.getByKeyOrThrow(eq("ENG-1"), eq(10L))).thenThrow(new ForbiddenOperationException("你不是该项目成员"));

        assertThrows(ForbiddenOperationException.class,
                () -> taskAttachmentService.upload("ENG-1", multipartFile, 10L));
        verify(taskAttachmentMapper, never()).insert(any());
    }

    @Test
    void downloadRejectsWhenFileSizeMissing() {
        Task task = new Task();
        task.setId(1L);
        when(taskQueryService.getByKeyOrThrow("ENG-1", 10L)).thenReturn(task);
        TaskAttachment att = new TaskAttachment();
        att.setId(51L);
        att.setTaskId(1L);
        att.setObjectKey("attachments/1/missing-size.bin");
        att.setFileSize(null);
        when(taskAttachmentMapper.selectOne(any())).thenReturn(att);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> taskAttachmentService.getAttachmentForDownload("ENG-1", 51L, 10L));
        assertEquals("附件缺少文件大小信息，拒绝下载", ex.getMessage());
        verify(objectStorageService, never()).openObjectStreamByKey(any());
    }

    @Test
    void downloadReturnsStreamWhenFileSizeValid() {
        Task task = new Task();
        task.setId(1L);
        when(taskQueryService.getByKeyOrThrow("ENG-1", 10L)).thenReturn(task);
        TaskAttachment att = new TaskAttachment();
        att.setId(52L);
        att.setTaskId(1L);
        att.setObjectKey("attachments/1/ok.bin");
        att.setFileName("ok.bin");
        att.setFileSize(100L);
        att.setContentType("application/octet-stream");
        when(taskAttachmentMapper.selectOne(any())).thenReturn(att);
        when(objectStorageService.openObjectStreamByKey("attachments/1/ok.bin"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        var download = taskAttachmentService.getAttachmentForDownload("ENG-1", 52L, 10L);

        assertEquals("ok.bin", download.fileName());
        assertEquals(100L, download.contentLength());
        verify(objectStorageService).openObjectStreamByKey("attachments/1/ok.bin");
    }
}
