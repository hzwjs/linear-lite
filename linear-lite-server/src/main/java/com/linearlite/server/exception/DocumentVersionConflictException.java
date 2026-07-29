package com.linearlite.server.exception;

public class DocumentVersionConflictException extends RuntimeException {
    private final Long currentVersion;

    public DocumentVersionConflictException(Long currentVersion) {
        super("文档已被其他修改更新");
        this.currentVersion = currentVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }
}
