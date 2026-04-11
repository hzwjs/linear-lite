package com.linearlite.server.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

@Component
@ConditionalOnProperty(prefix = "app.storage.r2", name = "enabled", havingValue = "true")
public class S3R2StorageClient implements R2StorageClient {

    private final S3Client s3Client;

    public S3R2StorageClient(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public void putObject(String bucket, String key, String contentType, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(content));
    }

    @Override
    public InputStream openObjectStream(String bucket, String key) {
        ResponseInputStream<?> stream = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return stream;
    }

    @Override
    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
