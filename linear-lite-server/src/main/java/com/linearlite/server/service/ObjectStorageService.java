package com.linearlite.server.service;

import com.linearlite.server.dto.ImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface ObjectStorageService {

    ImageUploadResponse uploadImage(MultipartFile file);

    ImageUploadResponse uploadAttachment(MultipartFile file, long taskId);

    ImageUploadResponse uploadProjectDocumentAttachment(
            MultipartFile file, long projectId, long documentId, long maxBytes);

    InputStream openObjectStreamByKey(String key);

    void deleteObjectByKey(String key);
}
