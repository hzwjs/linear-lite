package com.linearlite.server.service;

public interface R2StorageClient {

    void putObject(String bucket, String key, String contentType, byte[] content);

    void deleteObject(String bucket, String key);
}
