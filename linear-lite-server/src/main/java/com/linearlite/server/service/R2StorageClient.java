package com.linearlite.server.service;

public interface R2StorageClient {

    void putObject(String bucket, String key, String contentType, byte[] content);

    byte[] getObject(String bucket, String key);

    void deleteObject(String bucket, String key);
}
