package com.linearlite.server.service;

import com.linearlite.server.dto.ImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {

    ImageUploadResponse uploadImage(MultipartFile file);

    ImageUploadResponse uploadAttachment(MultipartFile file, long taskId);

    void deleteObjectByKey(String key);
}
