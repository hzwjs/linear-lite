package com.linearlite.server.service;

import com.linearlite.server.config.R2StorageProperties;
import com.linearlite.server.dto.ImageUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class R2ObjectStorageServiceTest {

    private R2StorageClient storageClient;
    private R2ObjectStorageService storageService;

    @BeforeEach
    void setUp() {
        storageClient = mock(R2StorageClient.class);
        R2StorageProperties properties = new R2StorageProperties();
        properties.setBucket("linear-lite-assets");
        properties.setPublicBaseUrl("https://cdn.example.com/");
        storageService = new R2ObjectStorageService(storageClient, properties);
    }

    @Test
    void uploadImageStoresFileAndBuildsPublicUrl() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo image.png",
                "image/png",
                "demo".getBytes()
        );

        ImageUploadResponse response = storageService.uploadImage(file);

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageClient).putObject(bucketCaptor.capture(), keyCaptor.capture(), typeCaptor.capture(), bytesCaptor.capture());

        assertEquals("linear-lite-assets", bucketCaptor.getValue());
        assertEquals("image/png", typeCaptor.getValue());
        assertEquals("demo".getBytes().length, bytesCaptor.getValue().length);
        assertTrue(keyCaptor.getValue().matches("task-images/\\d{4}/\\d{2}/[0-9a-f\\-]+-demo-image.png"));
        assertEquals("https://cdn.example.com/" + keyCaptor.getValue(), response.getUrl());
        assertEquals(keyCaptor.getValue(), response.getKey());
    }

    @Test
    void uploadImageRejectsUnsupportedType() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo.bmp",
                "image/bmp",
                "demo".getBytes()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> storageService.uploadImage(file));

        assertEquals("仅支持 png/jpg/jpeg/webp/gif/svg 图片", error.getMessage());
    }

    @Test
    void uploadImageAcceptsSvgType() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo.svg",
                "image/svg+xml",
                "<svg />".getBytes()
        );

        ImageUploadResponse response = storageService.uploadImage(file);

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageClient).putObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), typeCaptor.capture(), org.mockito.ArgumentMatchers.any(byte[].class));

        assertEquals("image/svg+xml", typeCaptor.getValue());
        assertTrue(response.getKey().endsWith(".svg"));
    }

    @Test
    void uploadImageRejectsOversizedFiles() {
        byte[] content = new byte[(int) R2ObjectStorageService.MAX_IMAGE_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "huge.png", "image/png", content);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> storageService.uploadImage(file));

        assertEquals("图片大小不能超过 10MB", error.getMessage());
    }
}
