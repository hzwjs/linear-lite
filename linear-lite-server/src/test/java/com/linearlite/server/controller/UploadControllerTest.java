package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.ImageUploadResponse;
import com.linearlite.server.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadControllerTest {

    private ObjectStorageService objectStorageService;
    private UploadController controller;

    @BeforeEach
    void setUp() {
        objectStorageService = Mockito.mock(ObjectStorageService.class);
        controller = new UploadController(objectStorageService);
    }

    @Test
    void uploadImageReturnsWrappedResponse() {
        MockMultipartFile file = new MockMultipartFile("file", "demo.png", "image/png", "demo".getBytes());
        when(objectStorageService.uploadImage(file)).thenReturn(
                new ImageUploadResponse("https://cdn.example.com/task-images/demo.png", "task-images/demo.png")
        );

        ResponseEntity<ApiResponse<ImageUploadResponse>> response = controller.uploadImage(file);

        verify(objectStorageService).uploadImage(file);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(200, response.getBody().getCode());
        assertEquals("https://cdn.example.com/task-images/demo.png", response.getBody().getData().getUrl());
    }

    @Test
    void uploadImageRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", new byte[0]);
        when(objectStorageService.uploadImage(file)).thenThrow(new IllegalArgumentException("图片文件不能为空"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> controller.uploadImage(file));

        assertEquals("图片文件不能为空", error.getMessage());
    }
}
