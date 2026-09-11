package com.linearlite.server.service;

public interface R2StorageClient {

    void putObject(String bucket, String key, String contentType, byte[] content);

    void putObject(String bucket, String key, String contentType, java.io.InputStream content, long contentLength);

    java.io.InputStream openObjectStream(String bucket, String key);

    void deleteObject(String bucket, String key);

    void copyObject(String bucket, String sourceKey, String targetKey);
}
