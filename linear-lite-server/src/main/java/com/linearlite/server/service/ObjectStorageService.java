package com.linearlite.server.service;

import com.linearlite.server.dto.ImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface ObjectStorageService {

    ImageUploadResponse uploadImage(MultipartFile file);

    ImageUploadResponse uploadAttachment(MultipartFile file, long taskId);

    ImageUploadResponse uploadProjectDocumentAttachment(
            InputStream content, long contentLength, String fileName, String contentType,
            long projectId, long documentId, long maxBytes);

    /** 上传已生成的文档图片缩略图，返回对象键。 */
    String uploadProjectDocumentThumbnail(byte[] content, String contentType, long projectId, long documentId);

    /** 服务端复制文档附件对象到目标文档命名空间，返回新对象键。 */
    String copyProjectDocumentAttachmentObject(String sourceKey, String fileName, long projectId, long documentId);

    InputStream openObjectStreamByKey(String key);

    void deleteObjectByKey(String key);
}
